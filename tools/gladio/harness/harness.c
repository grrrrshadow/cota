/*
 * Runs the Gladio shader converter (app/src/main/cpp/gladiorenderer/src/shader_converter.c)
 * on the Linux host. The GLES calls the converter makes are stubbed: glShaderSource keeps the
 * converted source, which is written to OUT_DIR so it can be checked with glslc.
 *
 *   harness OUT_DIR [-o NAME] VERTEX FRAGMENT   convert one program from files, -o binds
 *                                               fragment output 0 to NAME (glBindFragDataLocation)
 *   harness OUT_DIR --openttd                   convert the OpenTTD 1.10 and 1.50 programs
 */
#include "gl_context.h"
#include "gl_renderer.h"
#include "shader_converter.h"

pthread_mutex_t glx_context_mutex = PTHREAD_MUTEX_INITIALIZER;
thread_local GLRenderer* currentRenderer;

#define MAX_SHADERS 64
static GLuint nextId = 1;
static char* shaderSources[MAX_SHADERS];
static GLenum shaderTypes[MAX_SHADERS];

GL_APICALL GLuint GL_APIENTRY glCreateShader(GLenum type) {
    shaderTypes[nextId] = type;
    return nextId++;
}
GL_APICALL GLuint GL_APIENTRY glCreateProgram(void) { return nextId++; }
GL_APICALL void GL_APIENTRY glShaderSource(GLuint shader, GLsizei count, const GLchar* const* string, const GLint* length) {
    free(shaderSources[shader]);
    shaderSources[shader] = strdup(string[0]);
}
GL_APICALL void GL_APIENTRY glCompileShader(GLuint shader) {}
GL_APICALL void GL_APIENTRY glGetShaderiv(GLuint shader, GLenum pname, GLint* params) { *params = 1; }
GL_APICALL void GL_APIENTRY glGetProgramiv(GLuint program, GLenum pname, GLint* params) { *params = 1; }
GL_APICALL void GL_APIENTRY glGetShaderInfoLog(GLuint shader, GLsizei bufSize, GLsizei* length, GLchar* infoLog) { if (length) *length = 0; }
GL_APICALL void GL_APIENTRY glGetProgramInfoLog(GLuint program, GLsizei bufSize, GLsizei* length, GLchar* infoLog) { if (length) *length = 0; }
GL_APICALL void GL_APIENTRY glAttachShader(GLuint program, GLuint shader) {}
GL_APICALL void GL_APIENTRY glDetachShader(GLuint program, GLuint shader) {}
GL_APICALL void GL_APIENTRY glLinkProgram(GLuint program) {}
GL_APICALL void GL_APIENTRY glUseProgram(GLuint program) {}
GL_APICALL void GL_APIENTRY glDeleteProgram(GLuint program) {}
GL_APICALL void GL_APIENTRY glDeleteShader(GLuint shader) {}
GL_APICALL void GL_APIENTRY glBindAttribLocation(GLuint program, GLuint index, const GLchar* name) {}
GL_APICALL GLint GL_APIENTRY glGetAttribLocation(GLuint program, const GLchar* name) { return -1; }
GL_APICALL GLint GL_APIENTRY glGetUniformLocation(GLuint program, const GLchar* name) { return -1; }
GL_APICALL GLuint GL_APIENTRY glGetUniformBlockIndex(GLuint program, const GLchar* name) { return GL_INVALID_INDEX; }
GL_APICALL void GL_APIENTRY glUniformBlockBinding(GLuint program, GLuint index, GLuint binding) {}
GL_APICALL void GL_APIENTRY glUniform1i(GLint location, GLint v0) {}
GL_APICALL void GL_APIENTRY glUniform1f(GLint location, GLfloat v0) {}
GL_APICALL void GL_APIENTRY glUniform2f(GLint location, GLfloat v0, GLfloat v1) {}
GL_APICALL void GL_APIENTRY glUniform4fv(GLint location, GLsizei count, const GLfloat* value) {}
GL_APICALL void GL_APIENTRY glUniformMatrix4fv(GLint location, GLsizei count, GLboolean transpose, const GLfloat* value) {}
GL_APICALL void GL_APIENTRY glVertexAttrib4fv(GLuint index, const GLfloat* v) {}
ARBProgram* ARBProgram_getBound(GLenum target) { return NULL; }
void GLRenderer_disableVertexAttribute(GLRenderer* renderer, int index) {}
float* GLRenderer_getMatrixFromStack(GLRenderer* renderer, int index) { static float m[16]; return m; }
bool GLRenderer_useARBProgram(GLRenderer* renderer, bool fullUpdate) { return false; }

#include "openttd_shaders.h"
#define lengthof(x) (sizeof(x) / sizeof(x[0]))

static const char* outDir;

static GLuint createShader(GLenum type, const char** chunks, int count) {
    GLuint id = ShaderConverter_createShader(type);
    ArrayBuffer buffer = {0};
    for (int i = 0; i < count; i++) {
        int size = strlen(chunks[i]);
        ArrayBuffer_putInt(&buffer, size);
        ArrayBuffer_putBytes(&buffer, (void*)chunks[i], size);
    }
    buffer.position = 0;
    ShaderConverter_setShaderSource(id, count, &buffer);
    ShaderConverter_getShader(id)->compileStatus = COMPILE_STATUS_PENDING;
    ArrayBuffer_free(&buffer);
    return id;
}

static char* readFile(const char* path) {
    FILE* file = fopen(path, "rb");
    if (!file) {
        fprintf(stderr, "cannot open %s\n", path);
        exit(2);
    }
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    rewind(file);
    char* data = calloc(1, size + 1);
    if (fread(data, 1, size, file) != size) exit(2);
    fclose(file);
    return data;
}

static void writeShader(const char* label, GLuint shader) {
    char path[512];
    snprintf(path, sizeof(path), "%s/%s.%s", outDir, label, shaderTypes[shader] == GL_VERTEX_SHADER ? "vert" : "frag");
    FILE* file = fopen(path, "wb");
    if (!file || !shaderSources[shader]) {
        fprintf(stderr, "no converted source for %s\n", path);
        exit(3);
    }
    fputs(shaderSources[shader], file);
    fclose(file);
}

static void linkProgram(const char* label, GLuint vs, GLuint fs, const char* fragDataName) {
    GLuint program = ShaderConverter_createProgram();
    ShaderConverter_attachShader(program, vs);
    ShaderConverter_attachShader(program, fs);
    if (fragDataName) SparseArray_put(&ShaderConverter_getProgram(program)->fragDataLocations, 0, strdup(fragDataName));
    ShaderConverter_linkProgram(program);

    char name[256];
    snprintf(name, sizeof(name), "%s", label);
    writeShader(name, vs);
    writeShader(name, fs);
}

static void convertOpenTTD() {
    struct {
        const char* label;
        const char** vs;
        int vsCount;
        const char** fs;
        int fsCount;
        const char* fragDataName;
    } programs[] = {
        {"openttd110-vid", _vertex_shader_sprite, lengthof(_vertex_shader_sprite), _frag_shader_direct, lengthof(_frag_shader_direct), NULL},
        {"openttd110-pal", _vertex_shader_sprite, lengthof(_vertex_shader_sprite), _frag_shader_palette, lengthof(_frag_shader_palette), NULL},
        {"openttd110-remap", _vertex_shader_sprite, lengthof(_vertex_shader_sprite), _frag_shader_rgb_mask_blend, lengthof(_frag_shader_rgb_mask_blend), NULL},
        {"openttd110-sprite", _vertex_shader_sprite, lengthof(_vertex_shader_sprite), _frag_shader_sprite_blend, lengthof(_frag_shader_sprite_blend), NULL},
        {"openttd150-vid", _vertex_shader_sprite_150, lengthof(_vertex_shader_sprite_150), _frag_shader_direct_150, lengthof(_frag_shader_direct_150), "colour"},
        {"openttd150-pal", _vertex_shader_sprite_150, lengthof(_vertex_shader_sprite_150), _frag_shader_palette_150, lengthof(_frag_shader_palette_150), "colour"},
        {"openttd150-remap", _vertex_shader_sprite_150, lengthof(_vertex_shader_sprite_150), _frag_shader_rgb_mask_blend_150, lengthof(_frag_shader_rgb_mask_blend_150), "colour"},
        {"openttd150-sprite", _vertex_shader_sprite_150, lengthof(_vertex_shader_sprite_150), _frag_shader_sprite_blend_150, lengthof(_frag_shader_sprite_blend_150), "colour"},
    };

    for (int i = 0; i < lengthof(programs); i++) {
        // OpenTTD passes every line as its own string, so do the same
        GLuint vs = createShader(GL_VERTEX_SHADER, programs[i].vs, programs[i].vsCount);
        GLuint fs = createShader(GL_FRAGMENT_SHADER, programs[i].fs, programs[i].fsCount);
        linkProgram(programs[i].label, vs, fs, programs[i].fragDataName);
    }
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s OUT_DIR [-o NAME] VERTEX FRAGMENT | OUT_DIR --openttd\n", argv[0]);
        return 1;
    }

    currentRenderer = calloc(1, sizeof(GLRenderer));
    currentRenderer->clientState.shaders = calloc(1, sizeof(SparseArray));
    currentRenderer->clientState.programs = calloc(1, sizeof(SparseArray));
    outDir = argv[1];

    if (strcmp(argv[2], "--openttd") == 0) {
        convertOpenTTD();
        return 0;
    }

    int arg = 2;
    const char* fragDataName = NULL;
    if (strcmp(argv[arg], "-o") == 0 && argc > arg + 1) {
        fragDataName = argv[arg + 1];
        arg += 2;
    }
    if (argc < arg + 2) return 1;

    const char* vsSource = readFile(argv[arg]);
    const char* fsSource = readFile(argv[arg + 1]);
    GLuint vs = createShader(GL_VERTEX_SHADER, &vsSource, 1);
    GLuint fs = createShader(GL_FRAGMENT_SHADER, &fsSource, 1);

    const char* label = strrchr(argv[arg], '/') ? strrchr(argv[arg], '/') + 1 : argv[arg];
    char name[256];
    snprintf(name, sizeof(name), "%.*s", (int)(strrchr(label, '.') ? strrchr(label, '.') - label : strlen(label)), label);
    linkProgram(name, vs, fs, fragDataName);
    return 0;
}
