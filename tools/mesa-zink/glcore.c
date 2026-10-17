// Smoke test for the packaged libGL (see smoke-aarch64.sh): a core context of the asked version (as OpenTTD and
// WineD3D create one), a persistent coherent buffer (OpenTTD at GL >= 4.4), three swaps, then the window is read back
// to check that the frame reached the X server the right way up.
// Usage: glcore [MAJOR.MINOR]
#define _GNU_SOURCE
#define GL_GLEXT_PROTOTYPES
#include <GL/glx.h>
#include <GL/glext.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <dlfcn.h>
#include <execinfo.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef GLXContext (*CreateContextAttribs)(Display *, GLXFBConfig, GLXContext, Bool, const int *);

static void on_segv(int sig, siginfo_t *si, void *uc) {
    void *frames[64];
    int n = backtrace(frames, 64);
    fprintf(stderr, "SIGSEGV at %p\n", si->si_addr);
    for (int i = 0; i < n; i++) {
        Dl_info info;
        if (dladdr(frames[i], &info) && info.dli_fname)
            fprintf(stderr, "  #%d %s+0x%lx %s\n", i, info.dli_fname, (unsigned long)((char *)frames[i] - (char *)info.dli_fbase), info.dli_sname ? info.dli_sname : "");
    }
    _exit(139);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    struct sigaction sa = {0};
    sa.sa_sigaction = on_segv;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, NULL);

    const char *version = argc > 1 ? argv[1] : "3.3";
    int major = version[0] - '0', minor = version[2] - '0';
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) { printf("no display\n"); return 1; }
    int fbattribs[] = {GLX_RENDER_TYPE, GLX_RGBA_BIT, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_DOUBLEBUFFER, True,
                       GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, None};
    int count = 0;
    GLXFBConfig *configs = glXChooseFBConfig(dpy, DefaultScreen(dpy), fbattribs, &count);
    if (!configs || !count) { printf("no fbconfig\n"); return 1; }
    XVisualInfo *vi = glXGetVisualFromFBConfig(dpy, configs[0]);
    XSetWindowAttributes swa = {0};
    swa.colormap = XCreateColormap(dpy, RootWindow(dpy, vi->screen), vi->visual, AllocNone);
    Window win = XCreateWindow(dpy, RootWindow(dpy, vi->screen), 0, 0, 64, 64, 0, vi->depth, InputOutput, vi->visual,
                               CWColormap | CWBorderPixel, &swa);
    XMapWindow(dpy, win);
    XSync(dpy, False);

    CreateContextAttribs create = (CreateContextAttribs)glXGetProcAddressARB((const GLubyte *)"glXCreateContextAttribsARB");
    int ctxattribs[] = {GLX_CONTEXT_MAJOR_VERSION_ARB, major, GLX_CONTEXT_MINOR_VERSION_ARB, minor,
                        GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB, None};
    GLXContext ctx = create(dpy, configs[0], NULL, True, ctxattribs);
    if (!ctx || !glXMakeCurrent(dpy, win, ctx)) { printf("core %s context failed\n", version); return 1; }
    printf("GL_VERSION: %s\nGL_RENDERER: %s\n", glGetString(GL_VERSION), glGetString(GL_RENDERER));

    PFNGLBUFFERSTORAGEPROC bufferStorage = (PFNGLBUFFERSTORAGEPROC)glXGetProcAddressARB((const GLubyte *)"glBufferStorage");
    GLuint vao, buf;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(1, &buf);
    glBindBuffer(GL_ARRAY_BUFFER, buf);
    GLbitfield flags = GL_MAP_WRITE_BIT | GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
    bufferStorage(GL_ARRAY_BUFFER, 4096, NULL, flags);
    unsigned char *map = glMapBufferRange(GL_ARRAY_BUFFER, 0, 4096, flags);
    if (map) memset(map, 0x5a, 4096);
    glFinish();
    unsigned char back[16] = {0};
    glGetBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(back), back);
    int mapOk = map && back[0] == 0x5a && back[15] == 0x5a;
    printf("persistent coherent map: %s\n", mapOk ? "OK" : "FAILED");

    for (int frame = 0; frame < 3; frame++) {
        glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, 32, 32); // GL's bottom-left quarter green
        glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        glXSwapBuffers(dpy, win);
    }
    XSync(dpy, False);
    XImage *img = XGetImage(dpy, win, 0, 0, 64, 64, AllPlanes, ZPixmap);
    unsigned long top = XGetPixel(img, 48, 16) & 0xffffff, bottom = XGetPixel(img, 16, 48) & 0xffffff;
    int presentOk = top == 0xff0000 && bottom == 0x00ff00;
    printf("window pixels: top-right=%06lx bottom-left=%06lx\npresent: %s\n", top, bottom, presentOk ? "OK" : "FAILED");
    return mapOk && presentOk ? 0 : 1;
}
