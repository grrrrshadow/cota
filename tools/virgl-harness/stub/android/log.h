/* Linux stand-in for <android/log.h>: the renderer's printf() goes to stderr (HARNESS_QUIET=1 drops it). */
#ifndef HARNESS_ANDROID_LOG_H
#define HARNESS_ANDROID_LOG_H

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

int __android_log_print(int prio, const char *tag, const char *fmt, ...) __attribute__((format(printf, 3, 4)));

#endif
