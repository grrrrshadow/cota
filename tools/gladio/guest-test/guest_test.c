/*
 * Drives the guest libGL against fake_host.c (see run.sh). Each test runs in its own process
 * so a crash in one does not hide the others. Results that only the guest can see are checked
 * here, what reaches the host is checked by run.sh in the fake host's log.
 *
 *   guest_test newcontext|upload|readback|copybuffers|persistent|packbuffer
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>

#ifndef GLX_CONTEXT_MAJOR_VERSION_ARB
#define GLX_CONTEXT_MAJOR_VERSION_ARB 0x2091
#define GLX_CONTEXT_MINOR_VERSION_ARB 0x2092
#endif

// libGL only needs it for glXChooseVisual, the application normally brings libX11
XVisualInfo* XGetVisualInfo(Display* display, long mask, XVisualInfo* info, int* count) {
    *count = 0;
    return NULL;
}

typedef GLXContext (*CreateContextAttribsARB)(Display*, GLXFBConfig, GLXContext, Bool, const int*);

static int failures = 0;

static void check(const char* what, bool ok) {
    printf("%s %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) failures++;
}

static bool bytesAre(const unsigned char* data, int count, int first) {
    for (int i = 0; i < count; i++) if (data[i] != (unsigned char)(first + i)) return false;
    return true;
}

static bool bytesUntouched(const unsigned char* data, int count) {
    for (int i = 0; i < count; i++) if (data[i] != 0xee) return false;
    return true;
}

int main(int argc, char** argv) {
    const char* test = argc > 1 ? argv[1] : "";
    GLXContext context;

    if (strcmp(test, "newcontext") == 0) {
        context = glXCreateNewContext(NULL, NULL, GLX_RGBA_TYPE, NULL, True);
    }
    else {
        static const int attribs[] = {GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB, 3, 0};
        CreateContextAttribsARB createContextAttribs = (CreateContextAttribsARB)glXGetProcAddress((const GLubyte*)"glXCreateContextAttribsARB");
        context = createContextAttribs(NULL, NULL, NULL, True, attribs);
    }
    check("context created", context != NULL);
    if (!context || !glXMakeCurrent(NULL, 1, context)) return 1;

    if (strcmp(test, "upload") == 0) {
        unsigned char pixels[128];
        for (int i = 0; i < sizeof(pixels); i++) pixels[i] = i;
        // 8bpp sprite with rows padded to 4 bytes under the default alignment, like OpenTTD
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 5, 3, GL_RED, GL_UNSIGNED_BYTE, pixels);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 5, 3, GL_RED, GL_UNSIGNED_BYTE, pixels);
        // wined3d style sub-rectangle of a pitched image
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 8);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 1);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }
    else if (strcmp(test, "readback") == 0) {
        unsigned char pixels[64];
        // the host sends 3x2 RGB rows tightly packed (bytes 1..18), the default pack alignment is 4
        memset(pixels, 0xee, sizeof(pixels));
        glReadPixels(0, 0, 3, 2, GL_RGB, GL_UNSIGNED_BYTE, pixels);
        check("row 0 at offset 0", bytesAre(pixels, 9, 1));
        check("row padding untouched", bytesUntouched(pixels + 9, 3));
        check("row 1 at offset 12", bytesAre(pixels + 12, 9, 10));
        check("after last row untouched", bytesUntouched(pixels + 21, 3));

        memset(pixels, 0xee, sizeof(pixels));
        glPixelStorei(GL_PACK_ROW_LENGTH, 4);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 1);
        glPixelStorei(GL_PACK_SKIP_ROWS, 1);
        glReadPixels(0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        check("skipped rows/pixels untouched", bytesUntouched(pixels, 20));
        check("row 0 at offset 20", bytesAre(pixels + 20, 8, 1));
        check("row 1 at offset 36", bytesAre(pixels + 36, 8, 9));
    }
    else if (strcmp(test, "copybuffers") == 0) {
        glBindBuffer(GL_COPY_READ_BUFFER, 7);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 8);
        glBufferData(GL_COPY_WRITE_BUFFER, 64, NULL, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 3);
        float vertices[4] = {1.0f, 2.0f, 3.0f, 4.0f};
        glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
        check("copy buffer targets survived", true);
    }
    else if (strcmp(test, "persistent") == 0) {
        glBindBuffer(GL_ARRAY_BUFFER, 1);
        glBufferStorage(GL_ARRAY_BUFFER, 64, NULL, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT);
        float* vertices = glMapBufferRange(GL_ARRAY_BUFFER, 0, 64, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT);
        check("persistent mapping", vertices != NULL);
        if (vertices) for (int i = 0; i < 16; i++) vertices[i] = i;
        glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 16, (void*)0);
        glEnableVertexAttribArray(0);

        GLushort indices[3] = {0, 1, 2};
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 2);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(indices), indices, GL_STATIC_DRAW);
        glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, (void*)0);
        check("indexed draw from an element array buffer", true);
    }
    else if (strcmp(test, "packbuffer") == 0) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 9);
        glBufferData(GL_PIXEL_PACK_BUFFER, 64, NULL, GL_STREAM_READ);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, (void*)16);
        unsigned char* data = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY);
        check("pack buffer mapped", data != NULL);
        if (data) check("texture image written at offset 16", bytesAre(data + 16, 8, 0xa1));
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);

        glReadPixels(0, 0, 2, 1, GL_RGBA, GL_UNSIGNED_BYTE, (void*)32);
        data = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY);
        if (data) check("read pixels in the pack buffer once glReadPixels returns", bytesAre(data + 32, 8, 0x51));
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    }
    else if (strcmp(test, "newcontext") != 0) {
        printf("unknown test %s\n", test);
        return 1;
    }

    // Swapping waits for the host's reply, so everything before it has been handled
    glXSwapBuffers(NULL, 1);
    printf("DONE %s\n", test);
    return failures ? 1 : 0;
}
