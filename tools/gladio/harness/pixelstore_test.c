/*
 * Checks the pixel store size helpers in gladiorenderer/include/texture_utils.h, which the guest
 * uses for upload sizes and readback layout and the host for pack buffer readbacks.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#include "texture_utils.h"

static int failures = 0;

static void expect(const char* what, int actual, int expected) {
    if (actual != expected) {
        printf("FAIL %s: %d, expected %d\n", what, actual, expected);
        failures++;
    }
    else printf("PASS %s = %d\n", what, actual);
}

int main() {
    // OpenTTD uploads 8bpp sprites with rows padded to 4 bytes and the default alignment
    expect("R8 5x3 align 4 stride", computePixelRowStride(GL_RED, GL_UNSIGNED_BYTE, 5, 0, 4), 8);
    expect("R8 5x3 align 4 size", computePixelStoreDataSize(GL_RED, GL_UNSIGNED_BYTE, 5, 3, 1, 0, 0, 4, 0, 0, 0), 21);
    expect("R8 5x3 default alignment size", computePixelStoreDataSize(GL_RED, GL_UNSIGNED_BYTE, 5, 3, 1, 0, 0, 0, 0, 0, 0), 21);
    expect("R8 5x3 align 1 size", computePixelStoreDataSize(GL_RED, GL_UNSIGNED_BYTE, 5, 3, 1, 0, 0, 1, 0, 0, 0), 15);
    expect("RGBA 3x2 align 8 size", computePixelStoreDataSize(GL_RGBA, GL_UNSIGNED_BYTE, 3, 2, 1, 0, 0, 8, 0, 0, 0), 28);
    expect("RGB 4x2 row length 10 align 1 size", computePixelStoreDataSize(GL_RGB, GL_UNSIGNED_BYTE, 4, 2, 1, 10, 0, 1, 0, 0, 0), 42);
    // wined3d style: BGRA with a row length (pitch) and skips
    expect("BGRA 4x2 row length 8 skip 2,1 size", computePixelStoreDataSize(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 2, 1, 8, 0, 4, 2, 1, 0), 88);
    expect("RGBA 2x2x3 image height 4 skip image 1 size", computePixelStoreDataSize(GL_RGBA, GL_UNSIGNED_BYTE, 2, 2, 3, 0, 4, 4, 0, 0, 1), 112);
    expect("RGB565 3x2 align 4 size", computePixelStoreDataSize(GL_RGB, GL_UNSIGNED_SHORT_5_6_5, 3, 2, 1, 0, 0, 4, 0, 0, 0), 14);
    expect("depth float 2x2 size", computePixelStoreDataSize(GL_DEPTH_COMPONENT, GL_FLOAT, 2, 2, 1, 0, 0, 4, 0, 0, 0), 16);
    expect("depth24 stencil8 3x1 size", computePixelStoreDataSize(GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, 3, 1, 1, 0, 0, 4, 0, 0, 0), 12);
    expect("RGBA float 1x2 align 8 size", computePixelStoreDataSize(GL_RGBA, GL_FLOAT, 1, 2, 1, 0, 0, 8, 0, 0, 0), 32);
    expect("unknown type size", computePixelStoreDataSize(GL_RGBA, 0x1234, 4, 4, 1, 0, 0, 4, 0, 0, 0), 0);
    expect("empty image size", computePixelStoreDataSize(GL_RGBA, GL_UNSIGNED_BYTE, 0, 4, 1, 0, 0, 4, 0, 0, 0), 0);
    return failures ? 1 : 0;
}
