/* GL 3.3 core test client for the VirGL harness: what OpenTTD's OpenGL backend does every frame
 * in a small form. A core-profile context, VAO + VBO streamed with glBufferSubData, an R8 texture
 * (OpenTTD's sprite format) updated with glTexSubImage2D, GLSL 330, then glXSwapBuffers.
 * At the end it reads a pixel back and checks the colour, so a broken host fails loudly.
 * Usage: gl33test [frames] [width] [height]    (prints fps)
 *   GL33TEST_TEX=rgba8          RGBA8 sprite instead of R8
 *   GL33TEST_RESIZE_EVERY=N     resize the window every N frames (new back buffers on the host)
 */
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>

#include <X11/Xlib.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <GL/glext.h>
#include <GL/glxext.h>

#define GL_FUNCS(X) \
   X(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays) \
   X(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray) \
   X(PFNGLGENBUFFERSPROC, glGenBuffers) \
   X(PFNGLBINDBUFFERPROC, glBindBuffer) \
   X(PFNGLBUFFERDATAPROC, glBufferData) \
   X(PFNGLBUFFERSUBDATAPROC, glBufferSubData) \
   X(PFNGLCREATESHADERPROC, glCreateShader) \
   X(PFNGLSHADERSOURCEPROC, glShaderSource) \
   X(PFNGLCOMPILESHADERPROC, glCompileShader) \
   X(PFNGLGETSHADERIVPROC, glGetShaderiv) \
   X(PFNGLGETSHADERINFOLOGPROC, glGetShaderInfoLog) \
   X(PFNGLCREATEPROGRAMPROC, glCreateProgram) \
   X(PFNGLATTACHSHADERPROC, glAttachShader) \
   X(PFNGLLINKPROGRAMPROC, glLinkProgram) \
   X(PFNGLGETPROGRAMIVPROC, glGetProgramiv) \
   X(PFNGLUSEPROGRAMPROC, glUseProgram) \
   X(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation) \
   X(PFNGLUNIFORM1FPROC, glUniform1f) \
   X(PFNGLUNIFORM1IPROC, glUniform1i) \
   X(PFNGLVERTEXATTRIBPOINTERPROC, glVertexAttribPointer) \
   X(PFNGLENABLEVERTEXATTRIBARRAYPROC, glEnableVertexAttribArray) \
   X(PFNGLBINDFRAGDATALOCATIONPROC, glBindFragDataLocation) \
   X(PFNGLGETSTRINGIPROC, glGetStringi)

#define DECLARE(type, name) static type p_##name;
GL_FUNCS(DECLARE)

#define QUADS 2000

static const char *vs_src =
   "#version 330 core\n"
   "layout(location = 0) in vec2 pos;\n"
   "layout(location = 1) in vec2 uv;\n"
   "out vec2 v_uv;\n"
   "uniform float t;\n"
   "void main() { v_uv = uv; gl_Position = vec4(pos * (0.9 + 0.1 * sin(t)), 0.0, 1.0); }\n";

static const char *fs_src =
   "#version 330 core\n"
   "in vec2 v_uv;\n"
   "out vec4 color;\n"
   "uniform sampler2D sprite;\n"
   "void main() { float a = texture(sprite, v_uv).r; color = vec4(a, 0.5, 1.0 - a, 1.0); }\n";

static GLuint compile(GLenum type, const char *src)
{
   GLuint s = p_glCreateShader(type);
   GLint ok;
   p_glShaderSource(s, 1, &src, NULL);
   p_glCompileShader(s);
   p_glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   if (!ok) {
      char log[1024];
      p_glGetShaderInfoLog(s, sizeof(log), NULL, log);
      fprintf(stderr, "gl33test: shader error: %s\n", log);
      exit(1);
   }
   return s;
}

static double now_sec(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(int argc, char **argv)
{
   int frames = argc > 1 ? atoi(argv[1]) : 1000;
   int width = argc > 2 ? atoi(argv[2]) : 800;
   int height = argc > 3 ? atoi(argv[3]) : 600;

   Display *dpy = XOpenDisplay(NULL);
   if (!dpy) {
      fprintf(stderr, "gl33test: no display\n");
      return 1;
   }

   static const int fb_att[] = { GLX_X_RENDERABLE, True, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_RENDER_TYPE, GLX_RGBA_BIT,
                                 GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_DOUBLEBUFFER, True, None };
   int n;
   GLXFBConfig *fbc = glXChooseFBConfig(dpy, DefaultScreen(dpy), fb_att, &n);
   if (!fbc || !n) {
      fprintf(stderr, "gl33test: no fbconfig\n");
      return 1;
   }
   XVisualInfo *vi = glXGetVisualFromFBConfig(dpy, fbc[0]);
   XSetWindowAttributes swa = { .colormap = XCreateColormap(dpy, RootWindow(dpy, vi->screen), vi->visual, AllocNone),
                                .border_pixel = 0 };
   Window win = XCreateWindow(dpy, RootWindow(dpy, vi->screen), 0, 0, width, height, 0, vi->depth, InputOutput,
                              vi->visual, CWColormap | CWBorderPixel, &swa);
   XMapWindow(dpy, win);

   PFNGLXCREATECONTEXTATTRIBSARBPROC create_ctx =
      (PFNGLXCREATECONTEXTATTRIBSARBPROC)glXGetProcAddressARB((const GLubyte *)"glXCreateContextAttribsARB");
   static const int ctx_att[] = { GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB, 3,
                                  GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB, None };
   GLXContext ctx = create_ctx ? create_ctx(dpy, fbc[0], NULL, True, ctx_att) : NULL;
   if (!ctx) {
      fprintf(stderr, "gl33test: no GL 3.3 core context\n");
      return 1;
   }
   glXMakeCurrent(dpy, win, ctx);

#define LOAD(type, name) p_##name = (type)glXGetProcAddressARB((const GLubyte *)#name);
   GL_FUNCS(LOAD)

   printf("gl33test: GL_RENDERER %s\ngl33test: GL_VERSION %s\ngl33test: GLSL %s\n", glGetString(GL_RENDERER),
          glGetString(GL_VERSION), glGetString(GL_SHADING_LANGUAGE_VERSION));

   GLuint prog = p_glCreateProgram();
   p_glAttachShader(prog, compile(GL_VERTEX_SHADER, vs_src));
   p_glAttachShader(prog, compile(GL_FRAGMENT_SHADER, fs_src));
   p_glBindFragDataLocation(prog, 0, "color");
   p_glLinkProgram(prog);
   GLint ok;
   p_glGetProgramiv(prog, GL_LINK_STATUS, &ok);
   if (!ok) {
      fprintf(stderr, "gl33test: link failed\n");
      return 1;
   }
   p_glUseProgram(prog);
   GLint u_t = p_glGetUniformLocation(prog, "t");
   p_glUniform1i(p_glGetUniformLocation(prog, "sprite"), 0);

   GLuint vao, vbo, tex;
   p_glGenVertexArrays(1, &vao);
   p_glBindVertexArray(vao);
   p_glGenBuffers(1, &vbo);
   p_glBindBuffer(GL_ARRAY_BUFFER, vbo);
   size_t vbo_size = QUADS * 6 * 4 * sizeof(float);
   p_glBufferData(GL_ARRAY_BUFFER, vbo_size, NULL, GL_STREAM_DRAW);
   p_glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)0);
   p_glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)(2 * sizeof(float)));
   p_glEnableVertexAttribArray(0);
   p_glEnableVertexAttribArray(1);

   /* GL33TEST_TEX=rgba8 uses an RGBA8 sprite instead, to tell R8 problems from upload problems */
   bool rgba = getenv("GL33TEST_TEX") && !strcmp(getenv("GL33TEST_TEX"), "rgba8");
   GLenum tex_format = rgba ? GL_RGBA : GL_RED;
   glGenTextures(1, &tex);
   glBindTexture(GL_TEXTURE_2D, tex);
   glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
   glTexImage2D(GL_TEXTURE_2D, 0, rgba ? GL_RGBA8 : GL_R8, 64, 64, 0, tex_format, GL_UNSIGNED_BYTE, NULL);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

   float *verts = malloc(vbo_size);
   unsigned char sprite[64 * 64 * 4];
   glViewport(0, 0, width, height);

   int resize_every = getenv("GL33TEST_RESIZE_EVERY") ? atoi(getenv("GL33TEST_RESIZE_EVERY")) : 0;
   int full_width = width, full_height = height;

   double start = now_sec(), last = start;
   int last_frame = 0;
   for (int f = 0; f < frames; f++) {
      if (resize_every > 0 && f > 0 && f % resize_every == 0) {
         bool small = (f / resize_every) % 2;
         width = small ? full_width - 100 : full_width;
         height = small ? full_height - 60 : full_height;
         XResizeWindow(dpy, win, width, height);
         XSync(dpy, False);
         glViewport(0, 0, width, height);
      }
      /* streamed quads: a grid that wobbles, rewritten every frame */
      float *v = verts;
      for (int q = 0; q < QUADS; q++) {
         float x = -1.0f + 2.0f * (q % 50) / 50.0f, y = -1.0f + 2.0f * (q / 50) / 40.0f;
         float w = 2.0f / 50.0f * 0.9f, h = 2.0f / 40.0f * 0.9f;
         x += 0.002f * sinf(f * 0.1f + q);
         float quad[6][4] = { { x, y, 0, 0 }, { x + w, y, 1, 0 }, { x, y + h, 0, 1 },
                              { x + w, y, 1, 0 }, { x + w, y + h, 1, 1 }, { x, y + h, 0, 1 } };
         memcpy(v, quad, sizeof(quad));
         v += 24;
      }
      p_glBufferSubData(GL_ARRAY_BUFFER, 0, vbo_size, verts);

      /* a sprite upload every frame, like OpenTTD's sprite cache filling up */
      for (int i = 0; i < 64 * 64; i++) {
         if (rgba)
            sprite[i * 4] = sprite[i * 4 + 1] = sprite[i * 4 + 2] = sprite[i * 4 + 3] = (unsigned char)((i + f) & 0xff);
         else
            sprite[i] = (unsigned char)((i + f) & 0xff);
      }
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 64, 64, tex_format, GL_UNSIGNED_BYTE, sprite);

      glClearColor(0.1f, 0.2f, 0.3f, 1.0f);
      glClear(GL_COLOR_BUFFER_BIT);
      p_glUniform1f(u_t, f * 0.05f);
      glDrawArrays(GL_TRIANGLES, 0, QUADS * 6);
      glXSwapBuffers(dpy, win);

      double t = now_sec();
      if (t - last >= 5.0) {
         printf("gl33test: %d frames in %.1f seconds = %.1f FPS\n", f + 1 - last_frame, t - last, (f + 1 - last_frame) / (t - last));
         fflush(stdout);
         last = t;
         last_frame = f + 1;
      }
   }
   double secs = now_sec() - start;

   /* inside the first quad the red channel is the R8 sprite texel (0..255), a check that R8 uploads work */
   unsigned char px[4] = { 0 };
   glClear(GL_COLOR_BUFFER_BIT);
   glDrawArrays(GL_TRIANGLES, 0, QUADS * 6);
   glReadPixels(width / 2 + 5, height / 2 + 5, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
   printf("gl33test: quad pixel %d,%d,%d (red = sprite texel, should not be 0 everywhere)\n", px[0], px[1], px[2]);

   /* read the sprite texture back: tells a broken upload from broken sampling */
   unsigned char texels[64 * 64 * 4];
   memset(texels, 0, sizeof(texels));
   glGetTexImage(GL_TEXTURE_2D, 0, tex_format, GL_UNSIGNED_BYTE, texels);
   printf("gl33test: sprite texels read back %d %d %d (uploaded %d %d %d)\n", texels[0], texels[rgba ? 4 : 1], texels[rgba ? 8 : 2],
          sprite[0], sprite[rgba ? 4 : 1], sprite[rgba ? 8 : 2]);

   /* the clear colour shows between the quads: check one pixel of the gap row at the top */
   glClear(GL_COLOR_BUFFER_BIT);
   glReadPixels(width / 2, height - 1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
   bool good = abs(px[0] - 26) <= 2 && abs(px[1] - 51) <= 2 && abs(px[2] - 77) <= 2;
   printf("gl33test: %d frames in %.2f s = %.1f fps, readback %d,%d,%d %s, glGetError 0x%x\n", frames, secs, frames / secs,
          px[0], px[1], px[2], good ? "OK" : "WRONG", glGetError());

   glXMakeCurrent(dpy, None, NULL);
   glXDestroyContext(dpy, ctx);
   XDestroyWindow(dpy, win);
   XCloseDisplay(dpy);
   return good ? 0 : 2;
}
