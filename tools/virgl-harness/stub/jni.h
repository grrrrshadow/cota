/* Just enough of jni.h to compile app/src/main/cpp/virglrenderer on Linux.
 * The harness supplies the JNIEnv; only the three calls virgl_server*.c make exist. */
#ifndef HARNESS_JNI_H
#define HARNESS_JNI_H

#include <stdint.h>

typedef int32_t jint;
typedef int64_t jlong;
typedef uint8_t jboolean;
typedef void *jobject;
typedef jobject jclass;
typedef struct harness_method *jmethodID;

struct JNINativeInterface;
typedef const struct JNINativeInterface *JNIEnv;

struct JNINativeInterface {
   jclass (*GetObjectClass)(JNIEnv *env, jobject obj);
   jmethodID (*GetMethodID)(JNIEnv *env, jclass cls, const char *name, const char *sig);
   void (*CallVoidMethod)(JNIEnv *env, jobject obj, jmethodID method, ...);
};

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

#endif
