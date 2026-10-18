/* Linux test harness for the VirGL host (app/src/main/cpp/virglrenderer).
 *
 * Plays the part of the app around libvirglrenderer.so: one epoll thread for all
 * clients like XConnectorEpoll, a fake JNIEnv for the killConnection/flushFrontbuffer
 * callbacks, and flushFrontbuffer() doing the same GL as VirGLRendererComponent.java +
 * Texture.java (copy of the guest's framebuffer into the X drawable's texture).
 *
 * The callback signature tells which Java to emulate: "(II)V" is the old
 * Texture.copyFromReadBuffer(w, h) (glCopyTexImage2D every frame), "(III)V" the new
 * copyFromReadBuffer(w, h, sourceId) (glCopyTexSubImage2D into the storage it made before).
 *
 * Environment:
 *   VIRGL_SERVER_PATH     socket path (default /tmp/.virgl_test)
 *   HARNESS_STATS_EVERY   print stats every N presented frames (default 1000)
 *   HARNESS_DUMP          "N:file.ppm" writes the drawable texture after the N-th frame
 *   HARNESS_EXIT_IDLE=1   exit when the last client disconnects
 *   HARNESS_QUIET=1       drop the renderer's log output
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/epoll.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <X11/Xlib.h>

#include <jni.h>

jlong Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleNewConnection(JNIEnv *env, jobject obj, jint fd);
void Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleRequest(JNIEnv *env, jobject obj, jlong clientPtr);
void Java_com_winlator_xenvironment_components_VirGLRendererComponent_destroyClient(JNIEnv *env, jobject obj, jlong clientPtr);

/* Defined by libwinlator (gpu_helper.c) on Android: the GLRenderer's context, shared with VirGL. */
EGLContext globalEGLContext = EGL_NO_CONTEXT;

/* ---- call counters (the link uses --wrap for these) ---- */
static uint64_t n_make_current, n_client_wait, n_client_wait_blocking, n_requests;

EGLBoolean __real_eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx);
EGLBoolean __wrap_eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx)
{
   n_make_current++;
   return __real_eglMakeCurrent(dpy, draw, read, ctx);
}

GLenum __real_glClientWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeout);
GLenum __wrap_glClientWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeout)
{
   n_client_wait++;
   if (timeout)
      n_client_wait_blocking++;
   return __real_glClientWaitSync(sync, flags, timeout);
}

static bool quiet;

int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
   va_list ap;
   if (quiet)
      return 0;
   va_start(ap, fmt);
   fprintf(stderr, "[%s] ", tag);
   vfprintf(stderr, fmt, ap);
   fputc('\n', stderr);
   va_end(ap);
   return 0;
}

/* ---- connector ---- */
#define MAX_CLIENTS 32

struct harness_client {
   int fd;
   jlong ptr;
   uint64_t frames;
   double start;
   double cpu_start;
};

static struct harness_client clients[MAX_CLIENTS];
static int epoll_fd;
static bool exit_when_idle;
static volatile sig_atomic_t stop;

static double now_sec(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return ts.tv_sec + ts.tv_nsec / 1e9;
}

static double thread_cpu_sec(void)
{
   struct rusage ru;
   getrusage(RUSAGE_THREAD, &ru);
   return ru.ru_utime.tv_sec + ru.ru_utime.tv_usec / 1e6 + ru.ru_stime.tv_sec + ru.ru_stime.tv_usec / 1e6;
}

static double process_cpu_sec(void)
{
   struct rusage ru;
   getrusage(RUSAGE_SELF, &ru);
   return ru.ru_utime.tv_sec + ru.ru_utime.tv_usec / 1e6 + ru.ru_stime.tv_sec + ru.ru_stime.tv_usec / 1e6;
}

static struct harness_client *find_client(int fd)
{
   for (int i = 0; i < MAX_CLIENTS; i++)
      if (clients[i].ptr && clients[i].fd == fd)
         return &clients[i];
   return NULL;
}

static JNIEnv harness_env;

/* XConnectorEpoll_killConnection + VirGLRendererComponent.handleConnectionShutdown */
static void kill_client(int fd)
{
   struct harness_client *c = find_client(fd);
   if (!c)
      return;

   double secs = now_sec() - c->start;
   double cpu = thread_cpu_sec() - c->cpu_start;
   fprintf(stderr, "harness: client fd %d done: %llu frames in %.2f s = %.1f fps, server thread CPU %.1f ms/1000 frames\n",
           fd, (unsigned long long)c->frames, secs, secs > 0 ? c->frames / secs : 0.0,
           c->frames ? cpu * 1e6 / c->frames : 0.0);

   jlong ptr = c->ptr;
   c->ptr = 0;
   epoll_ctl(epoll_fd, EPOLL_CTL_DEL, fd, NULL);
   close(fd);
   Java_com_winlator_xenvironment_components_VirGLRendererComponent_destroyClient(&harness_env, NULL, ptr);

   if (exit_when_idle) {
      for (int i = 0; i < MAX_CLIENTS; i++)
         if (clients[i].ptr)
            return;
      stop = 1;
   }
}

/* ---- the Java side of flushFrontbuffer ---- */
struct drawable_texture {
   uint32_t drawable;
   short width, height;
   GLuint texture;
   /* Texture.copyFromReadBuffer(width, height, sourceId) state */
   int copy_source;
   short copy_width, copy_height;
};

#define MAX_DRAWABLES 64
static struct drawable_texture drawables[MAX_DRAWABLES];
static Display *x_display;
static uint64_t total_frames, n_realloc, n_subcopy, n_subcopy_failed, n_gl_errors, n_stale_errors;
static uint64_t stats_every = 1000;
static uint64_t dump_frame;
static const char *dump_path;

static struct drawable_texture *get_drawable(uint32_t id)
{
   for (int i = 0; i < MAX_DRAWABLES; i++)
      if (drawables[i].drawable == id)
         return &drawables[i];

   /* XServer.drawableManager.getDrawable(): the size is the window's */
   Window root;
   int x, y;
   unsigned w, h, bw, depth;
   if (!x_display || !XGetGeometry(x_display, id, &root, &x, &y, &w, &h, &bw, &depth))
      return NULL;

   for (int i = 0; i < MAX_DRAWABLES; i++) {
      if (drawables[i].drawable == 0) {
         drawables[i].drawable = id;
         drawables[i].width = w;
         drawables[i].height = h;
         XSelectInput(x_display, id, StructureNotifyMask);
         return &drawables[i];
      }
   }
   return NULL;
}

/* On the phone a resized window gets a new Drawable; here the texture stays and changes size,
 * which goes through the same reallocation check. */
static void process_x_events(void)
{
   while (x_display && XPending(x_display)) {
      XEvent ev;
      XNextEvent(x_display, &ev);
      if (ev.type != ConfigureNotify)
         continue;
      for (int i = 0; i < MAX_DRAWABLES; i++) {
         if (drawables[i].drawable == ev.xconfigure.window) {
            drawables[i].width = ev.xconfigure.width;
            drawables[i].height = ev.xconfigure.height;
         }
      }
   }
}

/* Texture.allocateTexture(width, height, null) */
static void allocate_texture(struct drawable_texture *d)
{
   glGenTextures(1, &d->texture);
   glActiveTexture(GL_TEXTURE0);
   glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
   glBindTexture(GL_TEXTURE_2D, d->texture);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
   glBindTexture(GL_TEXTURE_2D, 0);
}

/* Texture.copyFromReadBuffer() as of bebfcc1 */
static void copy_from_read_buffer(struct drawable_texture *d)
{
   if (!d->texture)
      allocate_texture(d);
   glBindTexture(GL_TEXTURE_2D, d->texture);
   glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 0, 0, d->width, d->height, 0);
   glBindTexture(GL_TEXTURE_2D, 0);
   glFlush();
   n_realloc++;
}

/* Texture.copyFromReadBuffer(width, height, sourceId) */
static void copy_from_read_buffer_serial(struct drawable_texture *d, int source)
{
   if (!d->texture)
      allocate_texture(d);
   glBindTexture(GL_TEXTURE_2D, d->texture);
   bool reallocate = source != d->copy_source || d->width != d->copy_width || d->height != d->copy_height;
   if (!reallocate) {
      for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++)
         n_stale_errors++;
      glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, d->width, d->height);
      reallocate = glGetError() != GL_NO_ERROR;
      if (reallocate)
         n_subcopy_failed++;
      else
         n_subcopy++;
   }
   if (reallocate) {
      glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 0, 0, d->width, d->height, 0);
      d->copy_source = source;
      d->copy_width = d->width;
      d->copy_height = d->height;
      n_realloc++;
   }
   glBindTexture(GL_TEXTURE_2D, 0);
   glFlush();
}

static void dump_texture(struct drawable_texture *d, const char *path)
{
   GLuint fb;
   size_t size = (size_t)d->width * d->height * 4;
   uint8_t *pixels = malloc(size);
   uint32_t hash = 2166136261u;

   glGenFramebuffers(1, &fb);
   glBindFramebuffer(GL_FRAMEBUFFER, fb);
   glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, d->texture, 0);
   glReadPixels(0, 0, d->width, d->height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
   glBindFramebuffer(GL_FRAMEBUFFER, 0);
   glDeleteFramebuffers(1, &fb);

   FILE *f = fopen(path, "wb");
   if (f) {
      fprintf(f, "P6\n%d %d\n255\n", d->width, d->height);
      /* GL rows are bottom-up; the renderer draws them with flipY=false, so write as is */
      for (int y = 0; y < d->height; y++)
         for (int x = 0; x < d->width; x++)
            fwrite(pixels + ((size_t)y * d->width + x) * 4, 1, 3, f);
      fclose(f);
   }
   for (size_t i = 0; i < size; i++) {
      if ((i & 3) == 3)
         continue;
      hash = (hash ^ pixels[i]) * 16777619u;
   }
   fprintf(stderr, "harness: frame %llu of drawable 0x%x (%dx%d) -> %s, rgb fnv1a %08x\n",
           (unsigned long long)total_frames, d->drawable, d->width, d->height, path, hash);
   free(pixels);
}

static void print_stats(void)
{
   static double last_time, last_thread_cpu, last_process_cpu;
   static uint64_t last_frames, last_mc, last_cw, last_cwb, last_req;
   double t = now_sec(), tc = thread_cpu_sec(), pc = process_cpu_sec();
   uint64_t frames = total_frames - last_frames;

   if (last_time != 0 && frames) {
      fprintf(stderr, "harness: %llu frames: %.1f fps | server thread CPU %.1f ms/1000f | process CPU %.1f ms/1000f |"
               " per frame: %.2f requests, %.2f eglMakeCurrent, %.2f glClientWaitSync (%.2f blocking) | realloc %llu subcopy %llu (%llu failed)\n",
              (unsigned long long)total_frames, frames / (t - last_time),
              (tc - last_thread_cpu) * 1e6 / frames, (pc - last_process_cpu) * 1e6 / frames,
              (double)(n_requests - last_req) / frames, (double)(n_make_current - last_mc) / frames,
              (double)(n_client_wait - last_cw) / frames, (double)(n_client_wait_blocking - last_cwb) / frames,
              (unsigned long long)n_realloc, (unsigned long long)n_subcopy, (unsigned long long)n_subcopy_failed);
   }
   last_time = t;
   last_thread_cpu = tc;
   last_process_cpu = pc;
   last_frames = total_frames;
   last_mc = n_make_current;
   last_cw = n_client_wait;
   last_cwb = n_client_wait_blocking;
   last_req = n_requests;
}

/* VirGLRendererComponent.flushFrontbuffer() */
static void flush_frontbuffer(int drawable_id, int framebuffer, bool new_java, int source)
{
   process_x_events();
   struct drawable_texture *d = get_drawable(drawable_id);
   if (!d)
      return;

   glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
   if (new_java)
      copy_from_read_buffer_serial(d, source);
   else
      copy_from_read_buffer(d);
   glBindFramebuffer(GL_FRAMEBUFFER, 0);

   /* a glCopyTexSubImage2D the format rules reject would leave the drawable stale */
   GLenum error = glGetError();
   if (error != GL_NO_ERROR && n_gl_errors++ < 10)
      fprintf(stderr, "harness: GL error 0x%x presenting frame %llu\n", error, (unsigned long long)total_frames + 1);

   total_frames++;
   for (int i = 0; i < MAX_CLIENTS; i++)
      if (clients[i].ptr)
         clients[i].frames++;   /* fine for the one-client benchmarks */

   if (dump_path && total_frames == dump_frame)
      dump_texture(d, dump_path);
   if (total_frames == 1 || total_frames % stats_every == 0)
      print_stats();
}

/* ---- fake JNIEnv ---- */
struct harness_method {
   const char *name;
   int int_args;
};

static struct harness_method method_kill = { "killConnection", 1 };
static struct harness_method method_flush = { "flushFrontbuffer", 0 };

static jclass env_get_object_class(JNIEnv *env, jobject obj)
{
   return obj;
}

static jmethodID env_get_method_id(JNIEnv *env, jclass cls, const char *name, const char *sig)
{
   struct harness_method *m = NULL;
   if (!strcmp(name, method_kill.name))
      m = &method_kill;
   else if (!strcmp(name, method_flush.name))
      m = &method_flush;
   if (!m) {
      fprintf(stderr, "harness: unknown method %s%s\n", name, sig);
      return NULL;
   }
   m->int_args = 0;
   for (const char *p = sig + 1; *p && *p != ')'; p++)
      if (*p == 'I')
         m->int_args++;
   static bool told;
   if (m == &method_flush && !told) {
      told = true;
      fprintf(stderr, "harness: flushFrontbuffer%s -> emulating %s\n", sig,
              m->int_args >= 3 ? "Texture.copyFromReadBuffer(w, h, sourceId) (glCopyTexSubImage2D)" : "Texture.copyFromReadBuffer(w, h) (glCopyTexImage2D)");
   }
   return m;
}

static void env_call_void_method(JNIEnv *env, jobject obj, jmethodID method, ...)
{
   va_list ap;
   va_start(ap, method);
   if (method == &method_kill) {
      int fd = va_arg(ap, int);
      kill_client(fd);
   }
   else if (method == &method_flush) {
      int drawable = va_arg(ap, int);
      int framebuffer = va_arg(ap, int);
      int source = method->int_args >= 3 ? va_arg(ap, int) : 0;
      flush_frontbuffer(drawable, framebuffer, method->int_args >= 3, source);
   }
   va_end(ap);
}

static const struct JNINativeInterface harness_interface = {
   .GetObjectClass = env_get_object_class,
   .GetMethodID = env_get_method_id,
   .CallVoidMethod = env_call_void_method,
};

static void on_signal(int sig)
{
   stop = 1;
}

/* Stands in for GLRenderer's context so the server shares objects with it like on the phone. */
static void create_global_context(void)
{
   EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   EGLint major, minor, n;
   EGLConfig conf;
   static const EGLint conf_att[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                                      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE };
   static const EGLint ctx_att[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };

   if (!eglInitialize(dpy, &major, &minor) || !eglBindAPI(EGL_OPENGL_ES_API) ||
       !eglChooseConfig(dpy, conf_att, &conf, 1, &n) || n != 1) {
      fprintf(stderr, "harness: EGL init failed (set EGL_PLATFORM=surfaceless)\n");
      exit(1);
   }
   globalEGLContext = eglCreateContext(dpy, conf, EGL_NO_CONTEXT, ctx_att);
   fprintf(stderr, "harness: EGL %d.%d %s, global context %p\n", major, minor,
           eglQueryString(dpy, EGL_VENDOR), globalEGLContext);
}

int main(int argc, char **argv)
{
   const char *path = getenv("VIRGL_SERVER_PATH");
   if (!path)
      path = "/tmp/.virgl_test";
   quiet = getenv("HARNESS_QUIET") && atoi(getenv("HARNESS_QUIET"));
   exit_when_idle = getenv("HARNESS_EXIT_IDLE") && atoi(getenv("HARNESS_EXIT_IDLE"));
   if (getenv("HARNESS_STATS_EVERY"))
      stats_every = strtoull(getenv("HARNESS_STATS_EVERY"), NULL, 10);
   if (getenv("HARNESS_DUMP")) {
      static char buf[4096];
      snprintf(buf, sizeof(buf), "%s", getenv("HARNESS_DUMP"));
      char *colon = strchr(buf, ':');
      if (colon) {
         *colon = '\0';
         dump_frame = strtoull(buf, NULL, 10);
         dump_path = colon + 1;
      }
   }

   signal(SIGPIPE, SIG_IGN);
   signal(SIGINT, on_signal);
   signal(SIGTERM, on_signal);

   harness_env = &harness_interface;
   x_display = XOpenDisplay(NULL);
   if (!x_display)
      fprintf(stderr, "harness: no X display, frames will not be presented\n");
   create_global_context();

   int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
   struct sockaddr_un addr = { .sun_family = AF_UNIX };
   if (strlen(path) >= sizeof(addr.sun_path)) {
      fprintf(stderr, "harness: socket path longer than %zu bytes: %s\n", sizeof(addr.sun_path) - 1, path);
      return 1;
   }
   snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
   unlink(path);
   if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0 || listen(server_fd, 16) < 0) {
      perror("harness: bind");
      return 1;
   }

   epoll_fd = epoll_create1(0);
   struct epoll_event ev = { .events = EPOLLIN, .data.fd = server_fd };
   epoll_ctl(epoll_fd, EPOLL_CTL_ADD, server_fd, &ev);
   fprintf(stderr, "harness: listening on %s\n", path);

   while (!stop) {
      struct epoll_event events[16];
      int n = epoll_wait(epoll_fd, events, 16, 500);
      if (n < 0) {
         if (errno == EINTR)
            continue;
         break;
      }
      for (int i = 0; i < n && !stop; i++) {
         int fd = events[i].data.fd;
         if (fd == server_fd) {
            int client_fd = accept(server_fd, NULL, NULL);
            if (client_fd < 0)
               continue;
            struct harness_client *c = NULL;
            for (int j = 0; j < MAX_CLIENTS; j++)
               if (!clients[j].ptr) { c = &clients[j]; break; }
            if (!c) {
               close(client_fd);
               continue;
            }
            c->fd = client_fd;
            c->frames = 0;
            c->start = now_sec();
            c->cpu_start = thread_cpu_sec();
            c->ptr = Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleNewConnection(&harness_env, NULL, client_fd);
            ev.events = EPOLLIN;
            ev.data.fd = client_fd;
            epoll_ctl(epoll_fd, EPOLL_CTL_ADD, client_fd, &ev);
            fprintf(stderr, "harness: client fd %d connected\n", client_fd);
         }
         else if (events[i].events & (EPOLLIN | EPOLLHUP | EPOLLERR)) {
            /* XConnectorEpoll with input buffer capacity 0: one handleRequest per wakeup */
            struct harness_client *c = find_client(fd);
            if (!c)
               continue;
            n_requests++;
            Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleRequest(&harness_env, NULL, c->ptr);
         }
      }
   }

   print_stats();
   fprintf(stderr, "harness: exit, %llu frames total, %llu GL errors while presenting, %llu left by the renderer\n",
           (unsigned long long)total_frames, (unsigned long long)n_gl_errors, (unsigned long long)n_stale_errors);
   return 0;
}
