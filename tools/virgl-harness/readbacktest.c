/* Readback test client for the VirGL harness: the GPU path of OpenTTD Decouple's 3D (three_d_gpu.cpp of the game)
 * in a small form, with the pixels checked.
 *
 * - a "video driver" context: GL 3.3 core on a mapped window, presenting frames (glXSwapBuffers);
 * - a "card" context: GL 3.3 core on a hidden window, created with the video context as share list
 *   (wglCreateContextAttribsARB(dc, video_rc, ...) in the game, glXCreateContextAttribsARB under Wine);
 * - the card draws, like the game:
 *   buildings of the turned map ("budovy plochy"): FBO of RGBA8 + R32F + DEPTH_COMPONENT32F renderbuffers, clear depth 0,
 *     GL_GEQUAL, the game's own MAP_BUILDING shaders (texelFetch of an RGBA8 texture uploaded as GL_BGRA /
 *     GL_UNSIGNED_INT_8_8_8_8_REV, light per corner), read back GL_BGRA/GL_UNSIGNED_INT_8_8_8_8_REV and GL_RED/GL_FLOAT;
 *   pictures ("ramec"): FBO of RGBA8 + R8 + DEPTH_COMPONENT24, read back GL_BGRA/8_8_8_8_REV and GL_RED/GL_UNSIGNED_BYTE
 *     with GL_PACK_ALIGNMENT 1;
 *   ground of the turned map ("zem plochy"): a 4096x4096 atlas and a big ground texture filled with glTexSubImage2D
 *     (GL_UNPACK_ROW_LENGTH), the big one moved into a bigger one with glBlitFramebuffer, the game's GROUND shaders
 *     into an RGBA8 FBO, read back with GL_PACK_ROW_LENGTH.
 *   Every readback is compared with the pixels computed here; a summary line per readback says how many are right,
 *   black (rgb 0, alpha not), zero (nothing drawn) or otherwise wrong.
 *
 * Usage: readbacktest [cycles]      (default 3)
 *   READBACK_THREADS=1   the video context presents on its own thread while the card works (as in the game);
 *                        0 (default) switches both contexts on one thread. Bruno's guest fails this one (duplicate
 *                        resource numbers, requests and answers of the two threads mixed on its socket):
 *                        guest-patches/ fixes it
 *   Run directly against the system's Mesa (no LD_LIBRARY_PATH) it checks itself: every readback must be right.
 *   READBACK_SHARE=0     the card's context without the share list
 *   READBACK_VERBOSE=1   the first wrong pixels of each readback
 * Exit status 0 when every pixel of every readback is right and no GL error came up.
 */
#define _GNU_SOURCE
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <X11/Xlib.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <GL/glext.h>
#include <GL/glxext.h>

#define GL_FUNCS(X) \
   X(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays) \
   X(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray) \
   X(PFNGLDELETEVERTEXARRAYSPROC, glDeleteVertexArrays) \
   X(PFNGLGENBUFFERSPROC, glGenBuffers) \
   X(PFNGLBINDBUFFERPROC, glBindBuffer) \
   X(PFNGLBUFFERDATAPROC, glBufferData) \
   X(PFNGLDELETEBUFFERSPROC, glDeleteBuffers) \
   X(PFNGLCREATESHADERPROC, glCreateShader) \
   X(PFNGLSHADERSOURCEPROC, glShaderSource) \
   X(PFNGLCOMPILESHADERPROC, glCompileShader) \
   X(PFNGLGETSHADERIVPROC, glGetShaderiv) \
   X(PFNGLGETSHADERINFOLOGPROC, glGetShaderInfoLog) \
   X(PFNGLDELETESHADERPROC, glDeleteShader) \
   X(PFNGLCREATEPROGRAMPROC, glCreateProgram) \
   X(PFNGLATTACHSHADERPROC, glAttachShader) \
   X(PFNGLLINKPROGRAMPROC, glLinkProgram) \
   X(PFNGLGETPROGRAMIVPROC, glGetProgramiv) \
   X(PFNGLGETPROGRAMINFOLOGPROC, glGetProgramInfoLog) \
   X(PFNGLUSEPROGRAMPROC, glUseProgram) \
   X(PFNGLDELETEPROGRAMPROC, glDeleteProgram) \
   X(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation) \
   X(PFNGLUNIFORM1IPROC, glUniform1i) \
   X(PFNGLUNIFORM1FPROC, glUniform1f) \
   X(PFNGLUNIFORM2FPROC, glUniform2f) \
   X(PFNGLUNIFORMMATRIX4FVPROC, glUniformMatrix4fv) \
   X(PFNGLVERTEXATTRIBPOINTERPROC, glVertexAttribPointer) \
   X(PFNGLENABLEVERTEXATTRIBARRAYPROC, glEnableVertexAttribArray) \
   X(PFNGLACTIVETEXTUREPROC, glActiveTexture) \
   X(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers) \
   X(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer) \
   X(PFNGLDELETEFRAMEBUFFERSPROC, glDeleteFramebuffers) \
   X(PFNGLFRAMEBUFFERRENDERBUFFERPROC, glFramebufferRenderbuffer) \
   X(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D) \
   X(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus) \
   X(PFNGLGENRENDERBUFFERSPROC, glGenRenderbuffers) \
   X(PFNGLBINDRENDERBUFFERPROC, glBindRenderbuffer) \
   X(PFNGLRENDERBUFFERSTORAGEPROC, glRenderbufferStorage) \
   X(PFNGLDELETERENDERBUFFERSPROC, glDeleteRenderbuffers) \
   X(PFNGLDRAWBUFFERSPROC, glDrawBuffers) \
   X(PFNGLBLITFRAMEBUFFERPROC, glBlitFramebuffer)

#define DECLARE(type, name) static type p_##name;
GL_FUNCS(DECLARE)

/* ---- the game's shaders, as they are in three_d_gpu.cpp ---- */
static const char *MAP_BUILDING_VERTEX =
   "#version 330 core\n"
   "layout(location = 0) in vec3 a_pos;\n"
   "layout(location = 1) in vec2 a_uv;\n"
   "layout(location = 2) in float a_light;\n"
   "uniform mat4 u_matrix;\n"
   "uniform vec2 u_depth_range;\n"
   "noperspective out vec2 v_uv;\n"
   "noperspective out float v_light;\n"
   "noperspective out float v_near;\n"
   "void main()\n"
   "{\n"
   "	gl_Position = u_matrix * vec4(a_pos, 1.0);\n"
   "	v_uv = a_uv;\n"
   "	v_light = a_light;\n"
   "	v_near = u_depth_range.x + (gl_Position.z + 1.0) * 0.5 * (u_depth_range.y - u_depth_range.x);\n"
   "}\n";

static const char *MAP_BUILDING_FRAGMENT =
   "#version 330 core\n"
   "uniform sampler2D u_tex;\n"
   "noperspective in vec2 v_uv;\n"
   "noperspective in float v_light;\n"
   "noperspective in float v_near;\n"
   "layout(location = 0) out vec4 o_colour;\n"
   "layout(location = 1) out vec4 o_near;\n"
   "const ivec2 NEAR[24] = ivec2[24](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1), ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1),\n"
   "		ivec2(2, 0), ivec2(-2, 0), ivec2(0, 2), ivec2(0, -2), ivec2(2, 1), ivec2(-2, 1), ivec2(2, -1), ivec2(-2, -1),\n"
   "		ivec2(3, 0), ivec2(-3, 0), ivec2(0, 3), ivec2(0, -3), ivec2(3, 1), ivec2(-3, 1), ivec2(3, -1), ivec2(-3, -1));\n"
   "void main()\n"
   "{\n"
   "	ivec2 size = textureSize(u_tex, 0);\n"
   "	ivec2 at = ivec2(floor(v_uv * vec2(size)));\n"
   "	if (at.x < 0 || at.y < 0 || at.x >= size.x || at.y >= size.y) discard;\n"
   "	vec4 t = texelFetch(u_tex, at, 0);\n"
   "	if (t == vec4(0.0)) {\n"
   "		for (int k = 0; k < 24; k++) {\n"
   "			ivec2 n = at + NEAR[k];\n"
   "			if (n.x < 0 || n.y < 0 || n.x >= size.x || n.y >= size.y) continue;\n"
   "			t = texelFetch(u_tex, n, 0);\n"
   "			if (t != vec4(0.0)) break;\n"
   "		}\n"
   "		if (t == vec4(0.0)) discard;\n"
   "	}\n"
   "	vec3 c = floor(t.rgb * 255.0 + 0.5);\n"
   "	float l = floor(max(0.0, v_light));\n"
   "	c = min(vec3(255.0), floor(c * l / 256.0));\n"
   "	o_colour = vec4(c / 255.0, t.a);\n"
   "	o_near = vec4(v_near, 0.0, 0.0, 1.0);\n"
   "}\n";

static const char *GROUND_VERTEX =
   "#version 330 core\n"
   "layout(location = 0) in vec2 a_pos;\n"
   "layout(location = 1) in vec2 a_uv;\n"
   "layout(location = 2) in vec4 a_bounds;\n"
   "layout(location = 3) in float a_atlas;\n"
   "uniform vec2 u_size;\n"
   "noperspective out vec2 v_uv;\n"
   "flat out vec4 v_bounds;\n"
   "flat out float v_atlas;\n"
   "void main()\n"
   "{\n"
   "	gl_Position = vec4(a_pos / u_size * 2.0 - 1.0, 0.0, 1.0);\n"
   "	v_uv = a_uv;\n"
   "	v_bounds = a_bounds;\n"
   "	v_atlas = a_atlas;\n"
   "}\n";

static const char *GROUND_FRAGMENT =
   "#version 330 core\n"
   "uniform sampler2D u_ground;\n"
   "uniform sampler2D u_atlas;\n"
   "noperspective in vec2 v_uv;\n"
   "flat in vec4 v_bounds;\n"
   "flat in float v_atlas;\n"
   "out vec4 o_colour;\n"
   "const ivec2 NEAR[24] = ivec2[24](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1), ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1),\n"
   "		ivec2(2, 0), ivec2(-2, 0), ivec2(0, 2), ivec2(0, -2), ivec2(2, 1), ivec2(-2, 1), ivec2(2, -1), ivec2(-2, -1),\n"
   "		ivec2(3, 0), ivec2(-3, 0), ivec2(0, 3), ivec2(0, -3), ivec2(3, 1), ivec2(-3, 1), ivec2(3, -1), ivec2(-3, -1));\n"
   "vec4 Texel(ivec2 at)\n"
   "{\n"
   "	ivec2 p = ivec2(v_bounds.xy) + at;\n"
   "	return v_atlas > 0.5 ? texelFetch(u_atlas, p, 0) : texelFetch(u_ground, p, 0);\n"
   "}\n"
   "void main()\n"
   "{\n"
   "	ivec2 size = ivec2(v_bounds.zw);\n"
   "	ivec2 at = ivec2(floor(v_uv));\n"
   "	if (at.x < 0 || at.y < 0 || at.x >= size.x || at.y >= size.y) discard;\n"
   "	vec4 t = Texel(at);\n"
   "	if (t == vec4(0.0)) {\n"
   "		for (int k = 0; k < 24; k++) {\n"
   "			ivec2 n = at + NEAR[k];\n"
   "			if (n.x < 0 || n.y < 0 || n.x >= size.x || n.y >= size.y) continue;\n"
   "			t = Texel(n);\n"
   "			if (t != vec4(0.0)) break;\n"
   "		}\n"
   "		if (t == vec4(0.0)) discard;\n"
   "	}\n"
   "	o_colour = t;\n"
   "}\n";

/* The pictures' FBO (colour + the R8 "lit" of the game's mesh program): the texel shaded by the light, the light in R8 */
static const char *LIT_VERTEX =
   "#version 330 core\n"
   "layout(location = 0) in vec3 a_pos;\n"
   "layout(location = 1) in vec2 a_uv;\n"
   "layout(location = 2) in float a_light;\n"
   "uniform mat4 u_matrix;\n"
   "noperspective out vec2 v_uv;\n"
   "flat out float v_light;\n"
   "void main()\n"
   "{\n"
   "	gl_Position = u_matrix * vec4(a_pos, 1.0);\n"
   "	v_uv = a_uv;\n"
   "	v_light = a_light;\n"
   "}\n";

static const char *LIT_FRAGMENT =
   "#version 330 core\n"
   "uniform sampler2D u_tex;\n"
   "noperspective in vec2 v_uv;\n"
   "flat in float v_light;\n"
   "layout(location = 0) out vec4 o_colour;\n"
   "layout(location = 1) out vec4 o_lit;\n"
   "void main()\n"
   "{\n"
   "	ivec2 size = textureSize(u_tex, 0);\n"
   "	vec4 t = texelFetch(u_tex, ivec2(floor(v_uv * vec2(size))), 0);\n"
   "	o_colour = t;\n"
   "	o_lit = vec4(v_light / 255.0, 0.0, 0.0, 1.0);\n"
   "}\n";

/* The video driver's frame: a full screen quad of an RGBA8 texture the card's context made (shared) */
static const char *VIDEO_VERTEX =
   "#version 330 core\n"
   "layout(location = 0) in vec2 a_pos;\n"
   "out vec2 v_uv;\n"
   "void main() { v_uv = a_pos * 0.5 + 0.5; gl_Position = vec4(a_pos, 0.0, 1.0); }\n";

static const char *VIDEO_FRAGMENT =
   "#version 330 core\n"
   "in vec2 v_uv;\n"
   "out vec4 o_colour;\n"
   "uniform sampler2D u_tex;\n"
   "uniform float u_t;\n"
   "void main() { o_colour = texture(u_tex, v_uv) * 0.5 + vec4(0.5 * fract(u_t), 0.25, 0.5, 0.5); }\n";

/* ---- test bookkeeping ---- */
static bool verbose;
static int failures, gl_errors;

static void check_gl(const char *what)
{
   GLenum e;
   while ((e = glGetError()) != GL_NO_ERROR) {
      printf("readbacktest: GL error 0x%x after %s\n", e, what);
      gl_errors++;
   }
}

static uint32_t hash32(uint32_t x)
{
   x ^= x >> 16;
   x *= 0x7feb352d;
   x ^= x >> 15;
   x *= 0x846ca68b;
   x ^= x >> 16;
   return x;
}

/* A texel as the game keeps it (Colour::data: A<<24 | R<<16 | G<<8 | B), never 0 (0 is "nothing" to the shaders) */
static uint32_t texel_value(uint32_t seed, int x, int y)
{
   uint32_t v = hash32(seed * 0x9e3779b9u + (uint32_t)y * 4099u + (uint32_t)x);
   return v | 0x01000000u | (v & 0x00800000u ? 0 : 0x80000000u);
}

static GLuint compile(GLenum type, const char *src)
{
   GLuint s = p_glCreateShader(type);
   GLint ok;
   p_glShaderSource(s, 1, &src, NULL);
   p_glCompileShader(s);
   p_glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   if (!ok) {
      char log[2048];
      p_glGetShaderInfoLog(s, sizeof(log), NULL, log);
      fprintf(stderr, "readbacktest: shader error: %s\n", log);
      exit(3);
   }
   return s;
}

static GLuint program(const char *vs, const char *fs)
{
   GLuint p = p_glCreateProgram();
   GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
   GLint ok;
   p_glAttachShader(p, v);
   p_glAttachShader(p, f);
   p_glLinkProgram(p);
   p_glDeleteShader(v);
   p_glDeleteShader(f);
   p_glGetProgramiv(p, GL_LINK_STATUS, &ok);
   if (!ok) {
      char log[2048];
      p_glGetProgramInfoLog(p, sizeof(log), NULL, log);
      fprintf(stderr, "readbacktest: link error: %s\n", log);
      exit(3);
   }
   return p;
}

/* ---- textures (TextureOnCard(), NewGroundTexture(), SendGroundRect()) ---- */
struct tex {
   GLuint name;
   int w, h;
   uint32_t seed;
};

static void texture_params(void)
{
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
}

static struct tex texture_on_card(int w, int h, uint32_t seed)
{
   struct tex t = { 0, w, h, seed };
   uint32_t *px = malloc((size_t)w * h * 4);
   for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
         px[y * w + x] = texel_value(seed, x, y);
   glGenTextures(1, &t.name);
   glBindTexture(GL_TEXTURE_2D, t.name);
   texture_params();
   glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
   glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, px);
   free(px);
   return t;
}

static GLuint new_ground_texture(int w, int h)
{
   GLuint name;
   glGenTextures(1, &name);
   glBindTexture(GL_TEXTURE_2D, name);
   texture_params();
   glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_BYTE, NULL);
   return name;
}

/* SendGroundRect(): rows of a bigger picture, GL_UNPACK_ROW_LENGTH its width */
static void send_ground_rect(const uint32_t *pixels, int pitch, int x, int y, int w, int h, int to_x, int to_y)
{
   glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
   glPixelStorei(GL_UNPACK_ROW_LENGTH, pitch);
   glTexSubImage2D(GL_TEXTURE_2D, 0, to_x + x, to_y + y, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels + (size_t)y * pitch + x);
   glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

/* ---- comparing a readback ---- */
struct tally {
   long ok, black, zero, bad, untouched;
};

static void tally_colour(struct tally *t, const char *what, int x, int y, uint32_t got, uint32_t want)
{
   if (got == want) {
      t->ok++;
      return;
   }
   if (got == 0xcdcdcdcdu)
      t->untouched++;
   else if (got == 0)
      t->zero++;
   else if ((got & 0x00ffffffu) == 0)
      t->black++;
   else
      t->bad++;
   if (verbose && t->black + t->zero + t->bad + t->untouched <= 5)
      printf("readbacktest:   %s %d,%d: got %08x want %08x\n", what, x, y, got, want);
}

static bool report(const char *what, int w, int h, const struct tally *t)
{
   long all = (long)w * h;
   bool good = t->ok == all;
   printf("readbacktest: %-34s %4dx%-4d %s: right %ld, black %ld, zero %ld, other %ld, not written %ld\n", what, w, h,
          good ? "OK   " : "WRONG", t->ok, t->black, t->zero, t->bad, t->untouched);
   if (!good)
      failures++;
   return good;
}

/* ---- buildings of the turned map (BuildingTargetOfSize(), DrawBuildingsOnCard()) ---- */
struct quad {
   int x0, y0, x1, y1;   /* in the picture, pixels, rows up */
   int tex;              /* index into the building textures */
   float light;          /* the corners' light (256 as it is) */
   float near;           /* how near: the larger, the nearer */
   int m;                /* pixels per texel: the texture's first (x1 - x0) / m texels across; past its end discarded */
};

struct picture {
   int w, h;
   int x, y;             /* its place in the frame */
   float dn, df;         /* depth_near, depth_far */
   int nquads;
   struct quad q[3];
};

#define NTEX 6
static struct tex btex[NTEX];
static const int btex_size[NTEX][2] = { { 64, 64 }, { 32, 32 }, { 16, 16 }, { 128, 128 }, { 5, 5 }, { 1, 1 } };

static GLuint bfbo, b_colour_rb, b_near_rb, b_depth_rb;
static int bfbo_w, bfbo_h;
static GLuint map_building_program, mb_tex, mb_matrix, mb_depth_range;
static GLuint quad_vao, quad_vbo;

static bool building_target_of_size(int w, int h)
{
   if (w <= bfbo_w && h <= bfbo_h)
      return true;
   bfbo_w = ((w > bfbo_w ? w : bfbo_w) + 255) / 256 * 256;
   bfbo_h = ((h > bfbo_h ? h : bfbo_h) + 255) / 256 * 256;
   p_glBindRenderbuffer(GL_RENDERBUFFER, b_colour_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, bfbo_w, bfbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, b_near_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_R32F, bfbo_w, bfbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, b_depth_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT32F, bfbo_w, bfbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, 0);
   p_glBindFramebuffer(GL_FRAMEBUFFER, bfbo);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, b_colour_rb);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_RENDERBUFFER, b_near_rb);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, b_depth_rb);
   GLenum status = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
   if (status != GL_FRAMEBUFFER_COMPLETE) {
      printf("readbacktest: buildings framebuffer %dx%d incomplete 0x%x\n", bfbo_w, bfbo_h, status);
      failures++;
      bfbo_w = bfbo_h = 0;
      return false;
   }
   return true;
}

/* Six corners (two triangles) of a quad: x, y, z, u, v, light. The texture coordinates make a texel m pixels
 * wide and high, so that a pixel's middle is never on a texel's edge: (px + 0.5) / m texels in. */
static int quad_corners(float *v, const struct quad *q)
{
   const float c[6][2] = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
   const struct tex *t = &btex[q->tex];
   for (int i = 0; i < 6; i++) {
      float *o = v + i * 6;
      o[0] = q->x0 + c[i][0] * (q->x1 - q->x0);
      o[1] = q->y0 + c[i][1] * (q->y1 - q->y0);
      o[2] = q->near;
      o[3] = c[i][0] * (q->x1 - q->x0) / (float)(q->m * t->w);
      o[4] = c[i][1] * (q->y1 - q->y0) / (float)(q->m * t->h);
      o[5] = q->light;
   }
   return 6;
}

static void bind_quad_vao(void)
{
   p_glBindVertexArray(quad_vao);
   p_glBindBuffer(GL_ARRAY_BUFFER, quad_vbo);
}

/* The colour the game's shader gives a pixel of a quad (as BGRA 8_8_8_8_REV reads it); false where it discards */
static bool building_colour(const struct quad *q, int px, int py, uint32_t *colour)
{
   const struct tex *t = &btex[q->tex];
   int tx = (px - q->x0) / q->m, ty = (py - q->y0) / q->m;
   if (tx >= t->w || ty >= t->h)
      return false;
   uint32_t texel = texel_value(t->seed, tx, ty);
   int l = (int)floor(q->light);
   uint32_t out = texel & 0xff000000u;
   for (int s = 0; s < 24; s += 8) {
      int c = (texel >> s) & 0xff;
      c = c * l / 256;
      if (c > 255)
         c = 255;
      out |= (uint32_t)c << s;
   }
   *colour = out;
   return true;
}

static void draw_buildings(struct picture *pics, int n, const char *what)
{
   static const GLenum both[2] = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 };
   /* one row of pictures side by side, frame 2048 wide as the game's */
   int frame_w = 2048, frame_h = 0, x = 0;
   for (int i = 0; i < n; i++) {
      pics[i].x = x;
      pics[i].y = 0;
      x += pics[i].w;
      if (pics[i].h > frame_h)
         frame_h = pics[i].h;
   }
   if (!building_target_of_size(frame_w, frame_h))
      return;
   p_glBindFramebuffer(GL_FRAMEBUFFER, bfbo);
   p_glDrawBuffers(2, both);
   glDisable(GL_DITHER);
   glDisable(GL_BLEND);
   glEnable(GL_SCISSOR_TEST);
   glScissor(0, 0, frame_w, frame_h);
   glDepthMask(GL_TRUE);
   glClearColor(0, 0, 0, 0);
   glClearDepth(0.0);
   glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
   glDisable(GL_SCISSOR_TEST);
   glEnable(GL_DEPTH_TEST);
   glDepthFunc(GL_GEQUAL);
   glFrontFace(GL_CCW);
   p_glUseProgram(map_building_program);
   p_glUniform1i(mb_tex, 0);
   p_glActiveTexture(GL_TEXTURE0);
   bind_quad_vao();
   for (int i = 0; i < n; i++) {
      struct picture *p = &pics[i];
      float verts[3 * 36];
      int count = 0;
      for (int k = 0; k < p->nquads; k++)
         count += quad_corners(verts + count * 6, &p->q[k]);
      p_glBufferData(GL_ARRAY_BUFFER, count * 6 * sizeof(float), verts, GL_STREAM_DRAW);
      glViewport(p->x, p->y, p->w, p->h);
      const float dn = p->dn, df = p->df, dz = df > dn ? 2.0f / (df - dn) : 1.0f;
      const float m[16] = { 2.0f / p->w, 0, 0, -1, 0, 2.0f / p->h, 0, -1, 0, 0, dz, -1 - dn * dz, 0, 0, 0, 1 };
      p_glUniformMatrix4fv(mb_matrix, 1, GL_TRUE, m);
      p_glUniform2f(mb_depth_range, dn, df);
      glDisable(GL_CULL_FACE);
      for (int k = 0; k < p->nquads; k++) {
         glBindTexture(GL_TEXTURE_2D, btex[p->q[k].tex].name);
         glDrawArrays(GL_TRIANGLES, k * 6, 6);
      }
   }
   glDisable(GL_DEPTH_TEST);
   p_glBindVertexArray(0);

   size_t count = (size_t)frame_w * frame_h;
   uint32_t *colours = malloc(count * 4);
   float *nearness = malloc(count * 4);
   memset(colours, 0xcd, count * 4);
   memset(nearness, 0xcd, count * 4);
   glPixelStorei(GL_PACK_ALIGNMENT, 4);
   glReadBuffer(GL_COLOR_ATTACHMENT0);
   glReadPixels(0, 0, frame_w, frame_h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, colours);
   glReadBuffer(GL_COLOR_ATTACHMENT1);
   glReadPixels(0, 0, frame_w, frame_h, GL_RED, GL_FLOAT, nearness);
   p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
   check_gl(what);

   struct tally tc = { 0 }, tn = { 0 };
   for (int y = 0; y < frame_h; y++) {
      for (int x = 0; x < frame_w; x++) {
         uint32_t want = 0;
         float want_near = 0.0f;
         for (int i = 0; i < n; i++) {
            const struct picture *p = &pics[i];
            if (x < p->x || x >= p->x + p->w || y < p->y || y >= p->y + p->h)
               continue;
            int px = x - p->x, py = y - p->y;
            float best = -1e30f;
            for (int k = 0; k < p->nquads; k++) {
               const struct quad *q = &p->q[k];
               /* GL_GEQUAL: a later quad at the same nearness wins too */
               uint32_t c;
               if (px >= q->x0 && px < q->x1 && py >= q->y0 && py < q->y1 && q->near >= best && building_colour(q, px, py, &c)) {
                  best = q->near;
                  want = c;
                  want_near = q->near;
               }
            }
         }
         size_t at = (size_t)y * frame_w + x;
         tally_colour(&tc, "colour", x, y, colours[at], want);
         uint32_t got_bits;
         memcpy(&got_bits, &nearness[at], 4);
         if (fabsf(nearness[at] - want_near) <= 1e-3f * (fabsf(want_near) + 1.0f))
            tn.ok++;
         else if (got_bits == 0xcdcdcdcdu)
            tn.untouched++;
         else if (nearness[at] == 0.0f)
            tn.zero++;
         else {
            tn.bad++;
            if (verbose && tn.bad <= 3)
               printf("readbacktest:   nearness %d,%d: got %g want %g\n", x, y, nearness[at], want_near);
         }
      }
   }
   char label[96];
   snprintf(label, sizeof(label), "%s BGRA 8888_REV", what);
   report(label, frame_w, frame_h, &tc);
   snprintf(label, sizeof(label), "%s RED FLOAT (R32F)", what);
   report(label, frame_w, frame_h, &tn);
   free(colours);
   free(nearness);
}

/* A set of pictures one frame high: picture 0 is the tallest; each a wall, a nearer roof over its right half and a
 * farther thing hidden behind the wall */
static int make_pictures(struct picture *pics, int frame_h, int cycle)
{
   int n = 0, x = 0;
   for (int i = 0; x < 2048 - 256 && n < 12; i++) {
      struct picture *p = &pics[n];
      int t = (i + cycle) % 4;          /* the four big textures: sizes 64, 32, 16, 128 */
      int ts = btex_size[t][0];
      int m = (i % 2) + 1;              /* texels of 1 or 2 pixels */
      p->w = ts * m;
      if (p->w > 256)
         p->w = ts;
      p->h = i == 0 ? frame_h : (frame_h * (3 + i % 3) / 6 > 8 ? frame_h * (3 + i % 3) / 6 : frame_h);
      p->dn = 1.0f + i;
      p->df = 100.0f + i;
      p->nquads = 3;
      /* a wall, the texture over it twice as big where it is two pixels a texel (the rest discarded) */
      p->q[0] = (struct quad){ 0, 0, p->w, p->h, t, 256.25f, 10.0f + i, m };
      /* a nearer roof over its right part, darker; the light above 256 brightens, clamped at 255 */
      p->q[1] = (struct quad){ p->w / 2, p->h / 4, p->w, p->h, (t + 1) % 4, i % 3 ? 128.25f : 300.25f, 50.0f + i, 1 + i % 2 };
      /* farther than the wall: hidden, but for where the wall's texture ends */
      p->q[2] = (struct quad){ 0, 0, p->w / 2 + 3, p->h / 2 + 3, 4 + (i % 2), 200.25f, 5.0f + i, 1 + i % 3 };
      x += p->w;
      n++;
   }
   return n;
}

/* ---- pictures with the lit buffer (TargetOfSize(): RGBA8 + R8 + DEPTH24) ---- */
static GLuint fbo, colour_rb, lit_rb, depth_rb;
static int fbo_w, fbo_h;
static GLuint lit_program, lit_tex, lit_matrix;

static bool target_of_size(int w, int h)
{
   if (w <= fbo_w && h <= fbo_h)
      return true;
   fbo_w = ((w > fbo_w ? w : fbo_w) + 255) / 256 * 256;
   fbo_h = ((h > fbo_h ? h : fbo_h) + 255) / 256 * 256;
   p_glBindRenderbuffer(GL_RENDERBUFFER, colour_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, fbo_w, fbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, lit_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_R8, fbo_w, fbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, depth_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, fbo_w, fbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, 0);
   p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colour_rb);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_RENDERBUFFER, lit_rb);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth_rb);
   GLenum status = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
   if (status != GL_FRAMEBUFFER_COMPLETE) {
      printf("readbacktest: picture framebuffer %dx%d incomplete 0x%x\n", fbo_w, fbo_h, status);
      failures++;
      fbo_w = fbo_h = 0;
      return false;
   }
   return true;
}

static void draw_lit(int w, int h, int cycle, const char *what)
{
   static const GLenum both[2] = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 };
   if (!target_of_size(w, h))
      return;
   /* a grid of quads, 64 pixels each, the textures 64/32/16/128 stretched over them in whole pixels per texel */
   int cols = (w + 63) / 64, rows = (h + 63) / 64;
   int nq = cols * rows;
   struct quad *qs = malloc(nq * sizeof(*qs));
   float *verts = malloc((size_t)nq * 36 * sizeof(float));
   int count = 0;
   for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
         struct quad *q = &qs[r * cols + c];
         int t = (r + c + cycle) % 3;   /* 64, 32, 16: 64 pixels a whole number of texels */
         *q = (struct quad){ c * 64, r * 64, c * 64 + 64, r * 64 + 64, t, (float)((r * 37 + c * 11 + cycle) % 256), 0.0f, 64 / btex_size[t][0] };
         count += quad_corners(verts + count * 6, q);
      }
   }
   p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
   p_glDrawBuffers(2, both);
   glDisable(GL_BLEND);
   glClearColor(0, 0, 0, 0);
   glClearDepth(1.0);
   glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
   glEnable(GL_DEPTH_TEST);
   glDepthFunc(GL_LESS);
   glViewport(0, 0, w, h);
   p_glUseProgram(lit_program);
   p_glUniform1i(lit_tex, 0);
   const float m[16] = { 2.0f / w, 0, 0, -1, 0, 2.0f / h, 0, -1, 0, 0, 0, 0, 0, 0, 0, 1 };
   p_glUniformMatrix4fv(lit_matrix, 1, GL_TRUE, m);
   bind_quad_vao();
   p_glBufferData(GL_ARRAY_BUFFER, count * 6 * sizeof(float), verts, GL_STREAM_DRAW);
   for (int i = 0; i < nq; i++) {
      glBindTexture(GL_TEXTURE_2D, btex[qs[i].tex].name);
      glDrawArrays(GL_TRIANGLES, i * 6, 6);
   }
   glDisable(GL_DEPTH_TEST);
   p_glBindVertexArray(0);

   size_t n = (size_t)w * h;
   uint32_t *colours = malloc(n * 4);
   uint8_t *lit = malloc(n);
   memset(colours, 0xcd, n * 4);
   memset(lit, 0xcd, n);
   glPixelStorei(GL_PACK_ALIGNMENT, 4);
   glReadBuffer(GL_COLOR_ATTACHMENT0);
   glReadPixels(0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, colours);
   glPixelStorei(GL_PACK_ALIGNMENT, 1);
   glReadBuffer(GL_COLOR_ATTACHMENT1);
   glReadPixels(0, 0, w, h, GL_RED, GL_UNSIGNED_BYTE, lit);
   p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
   check_gl(what);

   struct tally tc = { 0 }, tl = { 0 };
   for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
         const struct quad *q = &qs[(y / 64) * cols + x / 64];
         const struct tex *t = &btex[q->tex];
         uint32_t want = texel_value(t->seed, (x - q->x0) * t->w / 64, (y - q->y0) * t->h / 64);
         size_t at = (size_t)y * w + x;
         tally_colour(&tc, "colour", x, y, colours[at], want);
         uint8_t want_lit = (uint8_t)q->light;
         if (lit[at] == want_lit)
            tl.ok++;
         else if (lit[at] == 0xcd)
            tl.untouched++;
         else if (lit[at] == 0)
            tl.zero++;
         else {
            tl.bad++;
            if (verbose && tl.bad <= 3)
               printf("readbacktest:   lit %d,%d: got %d want %d\n", x, y, lit[at], want_lit);
         }
      }
   }
   char label[96];
   snprintf(label, sizeof(label), "%s BGRA 8888_REV", what);
   report(label, w, h, &tc);
   snprintf(label, sizeof(label), "%s RED UBYTE (R8)", what);
   report(label, w, h, &tl);
   free(colours);
   free(lit);
   free(qs);
   free(verts);
}

/* ---- ground of the turned map (GroundTextureOf(), GroundAtlasPlace(), DrawGroundOnCard()) ---- */
static GLuint gfbo, g_colour_rb, g_move_fbo[2], ground_vao, ground_vbo, ground_program;
static GLint gr_size, gr_ground, gr_atlas;
static int gfbo_w, gfbo_h;
static GLuint atlas, big;
static int big_w, big_h;
static uint32_t *big_pixels;   /* the game's kept picture of the ground: what the big texture should hold */

static bool ground_target_of_size(int w, int h)
{
   if (w <= gfbo_w && h <= gfbo_h)
      return true;
   gfbo_w = ((w > gfbo_w ? w : gfbo_w) + 255) / 256 * 256;
   gfbo_h = ((h > gfbo_h ? h : gfbo_h) + 255) / 256 * 256;
   p_glBindRenderbuffer(GL_RENDERBUFFER, g_colour_rb);
   p_glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, gfbo_w, gfbo_h);
   p_glBindRenderbuffer(GL_RENDERBUFFER, 0);
   p_glBindFramebuffer(GL_FRAMEBUFFER, gfbo);
   p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, g_colour_rb);
   GLenum status = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
   if (status != GL_FRAMEBUFFER_COMPLETE) {
      printf("readbacktest: ground framebuffer incomplete 0x%x\n", status);
      failures++;
      gfbo_w = gfbo_h = 0;
      return false;
   }
   return true;
}

/* The big ground texture: made and sent whole the first time; later moved into a bigger one by a blit, the old
 * part kept, and the new part sent in rects with GL_UNPACK_ROW_LENGTH */
static void ground_texture(int w, int h, int from_x, int from_y, int cycle, const char *what)
{
   uint32_t *pixels = malloc((size_t)w * h * 4);
   for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
         int ox = x - from_x, oy = y - from_y;
         pixels[(size_t)y * w + x] = big && ox >= 0 && oy >= 0 && ox < big_w && oy < big_h ? big_pixels[(size_t)oy * big_w + ox]
                                                                                           : texel_value(1000 + cycle, x, y);
      }
   if (big) {
      static const GLenum colour_only[1] = { GL_COLOR_ATTACHMENT0 };
      GLuint moved = new_ground_texture(w, h);
      glBindTexture(GL_TEXTURE_2D, 0);
      p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_move_fbo[1]);
      p_glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, moved, 0);
      p_glDrawBuffers(1, colour_only);
      glDisable(GL_SCISSOR_TEST);
      glClearColor(0, 0, 0, 0);
      glClear(GL_COLOR_BUFFER_BIT);
      p_glBindFramebuffer(GL_READ_FRAMEBUFFER, g_move_fbo[0]);
      p_glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, big, 0);
      glReadBuffer(GL_COLOR_ATTACHMENT0);
      int x0 = from_x > 0 ? from_x : 0, y0 = from_y > 0 ? from_y : 0;
      int x1 = from_x + big_w < w ? from_x + big_w : w, y1 = from_y + big_h < h ? from_y + big_h : h;
      if (x0 < x1 && y0 < y1)
         p_glBlitFramebuffer(x0 - from_x, y0 - from_y, x1 - from_x, y1 - from_y, x0, y0, x1, y1, GL_COLOR_BUFFER_BIT, GL_NEAREST);
      p_glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
      p_glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
      p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDeleteTextures(1, &big);
      big = moved;
      glBindTexture(GL_TEXTURE_2D, big);
      /* the part the old one did not have, in rects of 256 rows, rows as far apart as the whole picture's */
      for (int y = 0; y < h; y += 256) {
         int rh = y + 256 < h ? 256 : h - y;
         if (x0 > 0)
            send_ground_rect(pixels, w, 0, y, x0, rh, 0, 0);
         if (x1 < w)
            send_ground_rect(pixels, w, x1, y, w - x1, rh, 0, 0);
      }
      if (y0 > 0)
         send_ground_rect(pixels, w, x0, 0, x1 - x0, y0, 0, 0);
      if (y1 < h)
         send_ground_rect(pixels, w, x0, y1, x1 - x0, h - y1, 0, 0);
   } else {
      big = new_ground_texture(w, h);
      send_ground_rect(pixels, w, 0, 0, w, h, 0, 0);
   }
   glBindTexture(GL_TEXTURE_2D, 0);
   free(big_pixels);
   big_pixels = pixels;
   big_w = w;
   big_h = h;
   check_gl(what);
}

/* Atlas tiles: 64x32 pictures of their own seeds, placed in shelves */
#define ATLAS_TILES 24
static int atlas_place[ATLAS_TILES][2];

static void ground_atlas(int cycle)
{
   if (!atlas) {
      atlas = new_ground_texture(4096, 4096);
      glBindTexture(GL_TEXTURE_2D, 0);
   }
   glBindTexture(GL_TEXTURE_2D, atlas);
   uint32_t tile[64 * 32];
   for (int i = 0; i < ATLAS_TILES; i++) {
      for (int y = 0; y < 32; y++)
         for (int x = 0; x < 64; x++)
            tile[y * 64 + x] = texel_value(2000 + i + cycle * 100, x, y);
      atlas_place[i][0] = (i % 16) * 64 + cycle * 7;
      atlas_place[i][1] = (i / 16) * 32 + cycle * 64;
      send_ground_rect(tile, 64, 0, 0, 64, 32, atlas_place[i][0], atlas_place[i][1]);
   }
   glBindTexture(GL_TEXTURE_2D, 0);
   check_gl("atlas");
}

/* The ground block: the left part from the big texture, the right part in 64x32 tiles of the atlas */
static void draw_ground(int w, int h, int pitch, int src_x, int src_y, int cycle, const char *what)
{
   static const GLenum colour_only[1] = { GL_COLOR_ATTACHMENT0 };
   if (!ground_target_of_size(w, h))
      return;
   int split = (w / 2) & ~63;
   /* corners: x, y, u, v, bounds x, y, w, h, atlas */
   int max_quads = 1 + ((w - split + 63) / 64) * ((h + 31) / 32);
   float *corners = malloc((size_t)max_quads * 6 * 9 * sizeof(float));
   int n = 0;
#define CORNER(X, Y, U, V, BX, BY, BW, BH, A) do { float c_[9] = { X, Y, U, V, BX, BY, BW, BH, A }; memcpy(corners + n * 9, c_, sizeof(c_)); n++; } while (0)
#define QUAD(X0, Y0, X1, Y1, U0, V0, BX, BY, BW, BH, A) do { \
      CORNER(X0, Y0, U0, V0, BX, BY, BW, BH, A); CORNER(X1, Y0, U0 + (X1 - X0), V0, BX, BY, BW, BH, A); \
      CORNER(X0, Y1, U0, V0 + (Y1 - Y0), BX, BY, BW, BH, A); CORNER(X1, Y0, U0 + (X1 - X0), V0, BX, BY, BW, BH, A); \
      CORNER(X1, Y1, U0 + (X1 - X0), V0 + (Y1 - Y0), BX, BY, BW, BH, A); CORNER(X0, Y1, U0, V0 + (Y1 - Y0), BX, BY, BW, BH, A); } while (0)
   QUAD(0, 0, split, h, src_x, src_y, 0, 0, big_w, big_h, 0);
   int tiles_across = (w - split + 63) / 64;
   for (int ty = 0; ty * 32 < h; ty++)
      for (int tx = 0; tx < tiles_across; tx++) {
         int i = (ty * tiles_across + tx) % ATLAS_TILES;
         int x0 = split + tx * 64, y0 = ty * 32;
         int x1 = x0 + 64 < w ? x0 + 64 : w, y1 = y0 + 32 < h ? y0 + 32 : h;
         QUAD(x0, y0, x1, y1, 0, 0, atlas_place[i][0], atlas_place[i][1], 64, 32, 1);
      }
   p_glBindFramebuffer(GL_FRAMEBUFFER, gfbo);
   p_glDrawBuffers(1, colour_only);
   glViewport(0, 0, w, h);
   glDisable(GL_DITHER);
   glDisable(GL_BLEND);
   glDisable(GL_DEPTH_TEST);
   glDisable(GL_CULL_FACE);
   glDisable(GL_SCISSOR_TEST);
   glClearColor(0x12 / 255.0f, 0x34 / 255.0f, 0x56 / 255.0f, 1.0f);
   glClear(GL_COLOR_BUFFER_BIT);
   p_glUseProgram(ground_program);
   p_glUniform2f(gr_size, (float)w, (float)h);
   p_glUniform1i(gr_ground, 0);
   p_glUniform1i(gr_atlas, 1);
   p_glActiveTexture(GL_TEXTURE1);
   glBindTexture(GL_TEXTURE_2D, atlas);
   p_glActiveTexture(GL_TEXTURE0);
   glBindTexture(GL_TEXTURE_2D, big);
   p_glBindVertexArray(ground_vao);
   p_glBindBuffer(GL_ARRAY_BUFFER, ground_vbo);
   p_glBufferData(GL_ARRAY_BUFFER, (size_t)n * 9 * sizeof(float), corners, GL_STREAM_DRAW);
   glDrawArrays(GL_TRIANGLES, 0, n);
   p_glBindVertexArray(0);
   p_glActiveTexture(GL_TEXTURE1);
   glBindTexture(GL_TEXTURE_2D, 0);
   p_glActiveTexture(GL_TEXTURE0);
   glBindTexture(GL_TEXTURE_2D, 0);

   /* read straight into the block, its rows as far apart as its pitch; what lies between stays as it was */
   size_t size = (size_t)pitch * h;
   uint32_t *out = malloc(size * 4);
   memset(out, 0xcd, size * 4);
   glPixelStorei(GL_PACK_ALIGNMENT, 4);
   glPixelStorei(GL_PACK_ROW_LENGTH, pitch);
   glReadBuffer(GL_COLOR_ATTACHMENT0);
   glReadPixels(0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, out);
   glPixelStorei(GL_PACK_ROW_LENGTH, 0);
   p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
   check_gl(what);

   struct tally t = { 0 };
   long spill = 0;
   for (int y = 0; y < h; y++) {
      for (int x = 0; x < pitch; x++) {
         uint32_t got = out[(size_t)y * pitch + x];
         if (x >= w) {
            if (got != 0xcdcdcdcdu)
               spill++;
            continue;
         }
         uint32_t want;
         if (x < split) {
            want = big_pixels[(size_t)(src_y + y) * big_w + src_x + x];
         } else {
            int tx = (x - split) / 64, ty = y / 32;
            int i = (ty * tiles_across + tx) % ATLAS_TILES;
            want = texel_value(2000 + i + cycle * 100, (x - split) % 64, y % 32);
         }
         tally_colour(&t, "ground", x, y, got, want);
      }
   }
   report(what, w, h, &t);
   if (spill) {
      printf("readbacktest: %s wrote %ld pixels past its width (GL_PACK_ROW_LENGTH ignored)\n", what, spill);
      failures++;
   }
   free(out);
   free(corners);
#undef QUAD
#undef CORNER
}

/* ---- contexts ---- */
static Display *dpy;
static GLXFBConfig fbconfig;
static Window video_win, card_win;
static GLXContext video_ctx, card_ctx;
static PFNGLXCREATECONTEXTATTRIBSARBPROC create_ctx;
static volatile int stop_video;
static int video_frames;
static GLuint shared_texture;     /* made by the card, drawn by the video context (the view from the eyes shown straight) */
static pthread_mutex_t shared_lock = PTHREAD_MUTEX_INITIALIZER;   /* its name read and bound, or deleted, at a time */
static bool share = true;

static GLuint video_program, video_vao, video_vbo;
static GLint video_u_t, video_u_tex;

static void video_setup(void)
{
   video_program = program(VIDEO_VERTEX, VIDEO_FRAGMENT);
   video_u_t = p_glGetUniformLocation(video_program, "u_t");
   video_u_tex = p_glGetUniformLocation(video_program, "u_tex");
   static const float quad[12] = { -1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1 };
   p_glGenVertexArrays(1, &video_vao);
   p_glBindVertexArray(video_vao);
   p_glGenBuffers(1, &video_vbo);
   p_glBindBuffer(GL_ARRAY_BUFFER, video_vbo);
   p_glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
   p_glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), (void *)0);
   p_glEnableVertexAttribArray(0);
   p_glBindVertexArray(0);
}

static void video_frame(void)
{
   glViewport(0, 0, 640, 480);
   glClearColor(0.1f, 0.2f, 0.3f, 1.0f);
   glClear(GL_COLOR_BUFFER_BIT);
   p_glUseProgram(video_program);
   p_glUniform1f(video_u_t, video_frames * 0.01f);
   p_glUniform1i(video_u_tex, 0);
   p_glActiveTexture(GL_TEXTURE0);
   pthread_mutex_lock(&shared_lock);
   glBindTexture(GL_TEXTURE_2D, share ? shared_texture : 0);
   pthread_mutex_unlock(&shared_lock);
   p_glBindVertexArray(video_vao);
   glDrawArrays(GL_TRIANGLES, 0, 6);
   p_glBindVertexArray(0);
   glBindTexture(GL_TEXTURE_2D, 0);
   glXSwapBuffers(dpy, video_win);
   video_frames++;
}

static void *video_thread(void *arg)
{
   glXMakeCurrent(dpy, video_win, video_ctx);
   while (!stop_video)
      video_frame();
   glXMakeCurrent(dpy, None, NULL);
   return NULL;
}

static void card_setup(void)
{
   map_building_program = program(MAP_BUILDING_VERTEX, MAP_BUILDING_FRAGMENT);
   mb_tex = p_glGetUniformLocation(map_building_program, "u_tex");
   mb_matrix = p_glGetUniformLocation(map_building_program, "u_matrix");
   mb_depth_range = p_glGetUniformLocation(map_building_program, "u_depth_range");
   lit_program = program(LIT_VERTEX, LIT_FRAGMENT);
   lit_tex = p_glGetUniformLocation(lit_program, "u_tex");
   lit_matrix = p_glGetUniformLocation(lit_program, "u_matrix");
   ground_program = program(GROUND_VERTEX, GROUND_FRAGMENT);
   gr_size = p_glGetUniformLocation(ground_program, "u_size");
   gr_ground = p_glGetUniformLocation(ground_program, "u_ground");
   gr_atlas = p_glGetUniformLocation(ground_program, "u_atlas");

   p_glGenFramebuffers(1, &bfbo);
   p_glGenRenderbuffers(1, &b_colour_rb);
   p_glGenRenderbuffers(1, &b_near_rb);
   p_glGenRenderbuffers(1, &b_depth_rb);
   p_glGenFramebuffers(1, &fbo);
   p_glGenRenderbuffers(1, &colour_rb);
   p_glGenRenderbuffers(1, &lit_rb);
   p_glGenRenderbuffers(1, &depth_rb);
   p_glGenFramebuffers(1, &gfbo);
   p_glGenRenderbuffers(1, &g_colour_rb);
   p_glGenFramebuffers(2, g_move_fbo);

   p_glGenVertexArrays(1, &quad_vao);
   p_glBindVertexArray(quad_vao);
   p_glGenBuffers(1, &quad_vbo);
   p_glBindBuffer(GL_ARRAY_BUFFER, quad_vbo);
   p_glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)0);
   p_glEnableVertexAttribArray(0);
   p_glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)(3 * sizeof(float)));
   p_glEnableVertexAttribArray(1);
   p_glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void *)(5 * sizeof(float)));
   p_glEnableVertexAttribArray(2);
   p_glGenVertexArrays(1, &ground_vao);
   p_glBindVertexArray(ground_vao);
   p_glGenBuffers(1, &ground_vbo);
   p_glBindBuffer(GL_ARRAY_BUFFER, ground_vbo);
   p_glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 9 * sizeof(float), (void *)0);
   p_glEnableVertexAttribArray(0);
   p_glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 9 * sizeof(float), (void *)(2 * sizeof(float)));
   p_glEnableVertexAttribArray(1);
   p_glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, 9 * sizeof(float), (void *)(4 * sizeof(float)));
   p_glEnableVertexAttribArray(2);
   p_glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, 9 * sizeof(float), (void *)(8 * sizeof(float)));
   p_glEnableVertexAttribArray(3);
   p_glBindVertexArray(0);

   for (int i = 0; i < NTEX; i++)
      btex[i] = texture_on_card(btex_size[i][0], btex_size[i][1], 100 + i);
   glFlush();   /* made in this context, used in the video one */
   pthread_mutex_lock(&shared_lock);
   shared_texture = btex[0].name;
   pthread_mutex_unlock(&shared_lock);
   check_gl("card setup");
}

/* ForgetCard() + EndCard(): everything the card made, gone */
static void card_teardown(void)
{
   /* deleted while the video context may still have it bound: it lives on until unbound there */
   pthread_mutex_lock(&shared_lock);
   shared_texture = 0;
   for (int i = 0; i < NTEX; i++)
      glDeleteTextures(1, &btex[i].name);
   pthread_mutex_unlock(&shared_lock);
   if (atlas)
      glDeleteTextures(1, &atlas);
   if (big)
      glDeleteTextures(1, &big);
   atlas = big = 0;
   free(big_pixels);
   big_pixels = NULL;
   p_glDeleteFramebuffers(1, &bfbo);
   p_glDeleteFramebuffers(1, &fbo);
   p_glDeleteFramebuffers(1, &gfbo);
   p_glDeleteFramebuffers(2, g_move_fbo);
   GLuint rbs[] = { b_colour_rb, b_near_rb, b_depth_rb, colour_rb, lit_rb, depth_rb, g_colour_rb };
   p_glDeleteRenderbuffers(7, rbs);
   bfbo_w = bfbo_h = fbo_w = fbo_h = gfbo_w = gfbo_h = 0;
   p_glDeleteBuffers(1, &quad_vbo);
   p_glDeleteBuffers(1, &ground_vbo);
   p_glDeleteVertexArrays(1, &quad_vao);
   p_glDeleteVertexArrays(1, &ground_vao);
   p_glDeleteProgram(map_building_program);
   p_glDeleteProgram(lit_program);
   p_glDeleteProgram(ground_program);
   check_gl("card teardown");
}

static bool threads;

static void card_current(void)
{
   if (!glXMakeCurrent(dpy, card_win, card_ctx)) {
      fprintf(stderr, "readbacktest: glXMakeCurrent of the card's context failed\n");
      exit(1);
   }
}

/* One round of the game's pictures, as in 3d_karta.log: buildings 2048x162, 2048x86, 2048x48, 2048x164, the pictures'
 * FBO, then the ground (atlas, big texture, moved) 495x696, 290x86 */
static void card_cycle(int cycle)
{
   static const int heights[] = { 162, 86, 48, 164 };
   struct picture pics[12];
   char what[64];
   for (int i = 0; i < 4; i++) {
      int n = make_pictures(pics, heights[i], cycle);
      snprintf(what, sizeof(what), "buildings %d", cycle * 4 + i + 1);
      draw_buildings(pics, n, what);
      if (!threads) {
         glXMakeCurrent(dpy, video_win, video_ctx);
         video_frame();
         card_current();
      }
   }
   snprintf(what, sizeof(what), "pictures (lit R8) %d", cycle + 1);
   draw_lit(495, 696, cycle, what);
   draw_lit(2048, 162, cycle + 1, what);

   ground_atlas(cycle);
   if (cycle == 0)
      ground_texture(1376, 1856, 0, 0, cycle, "ground texture 1376x1856");
   else
      ground_texture(1376 + 800 * (cycle % 2), 1856, 64 * (cycle % 3), 0, cycle, "ground texture moved");
   snprintf(what, sizeof(what), "ground %d", cycle + 1);
   draw_ground(495, 696, 1280, 100, 200, cycle, what);
   draw_ground(290, 86, 1280, 7, 9, cycle, what);
   if (!threads) {
      glXMakeCurrent(dpy, video_win, video_ctx);
      video_frame();
      card_current();
   }
}

int main(int argc, char **argv)
{
   int cycles = argc > 1 ? atoi(argv[1]) : 3;
   threads = getenv("READBACK_THREADS") && atoi(getenv("READBACK_THREADS"));
   share = !getenv("READBACK_SHARE") || atoi(getenv("READBACK_SHARE"));
   verbose = getenv("READBACK_VERBOSE") && atoi(getenv("READBACK_VERBOSE"));

   XInitThreads();
   dpy = XOpenDisplay(NULL);
   if (!dpy) {
      fprintf(stderr, "readbacktest: no display\n");
      return 1;
   }
   static const int fb_att[] = { GLX_X_RENDERABLE, True, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_RENDER_TYPE, GLX_RGBA_BIT,
                                 GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_DOUBLEBUFFER, True, None };
   int n;
   GLXFBConfig *fbc = glXChooseFBConfig(dpy, DefaultScreen(dpy), fb_att, &n);
   if (!fbc || !n) {
      fprintf(stderr, "readbacktest: no fbconfig\n");
      return 1;
   }
   fbconfig = fbc[0];
   XVisualInfo *vi = glXGetVisualFromFBConfig(dpy, fbconfig);
   XSetWindowAttributes swa = { .colormap = XCreateColormap(dpy, RootWindow(dpy, vi->screen), vi->visual, AllocNone),
                                .border_pixel = 0 };
   video_win = XCreateWindow(dpy, RootWindow(dpy, vi->screen), 0, 0, 640, 480, 0, vi->depth, InputOutput, vi->visual,
                             CWColormap | CWBorderPixel, &swa);
   XMapWindow(dpy, video_win);
   /* the card's hidden window of the same pixel format, never mapped */
   card_win = XCreateWindow(dpy, RootWindow(dpy, vi->screen), 0, 0, 1, 1, 0, vi->depth, InputOutput, vi->visual,
                            CWColormap | CWBorderPixel, &swa);
   XSync(dpy, False);

   create_ctx = (PFNGLXCREATECONTEXTATTRIBSARBPROC)glXGetProcAddressARB((const GLubyte *)"glXCreateContextAttribsARB");
   static const int ctx_att[] = { GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB, 3,
                                  GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB, None };
   video_ctx = create_ctx ? create_ctx(dpy, fbconfig, NULL, True, ctx_att) : NULL;
   if (!video_ctx || !glXMakeCurrent(dpy, video_win, video_ctx)) {
      fprintf(stderr, "readbacktest: no GL 3.3 core context\n");
      return 1;
   }
#define LOAD(type, name) p_##name = (type)glXGetProcAddressARB((const GLubyte *)#name);
   GL_FUNCS(LOAD)
   printf("readbacktest: GL_RENDERER %s, GL_VERSION %s, %s, %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION),
          threads ? "video on its own thread" : "one thread", share ? "card shares the video context" : "card not shared");
   video_setup();
   video_frame();

   /* StartContext(): first a legacy context made current, then the 3.3 core one sharing the video driver's */
   GLXContext first = glXCreateNewContext(dpy, fbconfig, GLX_RGBA_TYPE, NULL, True);
   if (first)
      glXMakeCurrent(dpy, card_win, first);
   card_ctx = create_ctx(dpy, fbconfig, share ? video_ctx : NULL, True, ctx_att);
   if (!card_ctx) {
      fprintf(stderr, "readbacktest: no shared GL 3.3 core context\n");
      return 1;
   }
   card_current();
   if (first)
      glXDestroyContext(dpy, first);
   card_setup();
   glFinish();

   pthread_t vt;
   if (threads) {
      /* the video context belongs to its thread from now on */
      glXMakeCurrent(dpy, None, NULL);
      card_current();
      pthread_create(&vt, NULL, video_thread, NULL);
   }

   for (int c = 0; c < cycles; c++)
      card_cycle(c);

   /* ForgetCard() / EndCard() while the video context goes on, then a new card the same way (the game's "vse z karty
    * pryc" and a context made again after a full screen toggle) */
   card_teardown();
   glXMakeCurrent(dpy, None, NULL);
   glXDestroyContext(dpy, card_ctx);
   card_ctx = create_ctx(dpy, fbconfig, share ? video_ctx : NULL, True, ctx_att);
   card_current();
   card_setup();
   card_cycle(cycles);
   card_teardown();
   glXMakeCurrent(dpy, None, NULL);
   glXDestroyContext(dpy, card_ctx);

   if (threads) {
      stop_video = 1;
      pthread_join(vt, NULL);
   }
   glXMakeCurrent(dpy, video_win, video_ctx);
   video_frame();
   check_gl("video");
   glXMakeCurrent(dpy, None, NULL);
   glXDestroyContext(dpy, video_ctx);
   XDestroyWindow(dpy, card_win);
   XDestroyWindow(dpy, video_win);
   XCloseDisplay(dpy);
   printf("readbacktest: %s: %d wrong readbacks, %d GL errors, %d video frames\n", failures || gl_errors ? "FAIL" : "PASS",
          failures, gl_errors, video_frames);
   return failures || gl_errors ? 2 : 0;
}
