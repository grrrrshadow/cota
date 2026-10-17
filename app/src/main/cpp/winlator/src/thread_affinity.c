#define _GNU_SOURCE
#include <jni.h>
#include <sched.h>
#include <stdint.h>

JNIEXPORT jint JNICALL
Java_com_winlator_core_ThreadAffinity_setMask(JNIEnv *env, jclass obj, jint mask) {
    cpu_set_t oldSet, newSet;
    if (sched_getaffinity(0, sizeof(cpu_set_t), &oldSet) != 0) return 0;

    CPU_ZERO(&newSet);
    for (int i = 0; i < 32; i++) if ((uint32_t)mask & (1u << i)) CPU_SET(i, &newSet);
    if (sched_setaffinity(0, sizeof(cpu_set_t), &newSet) != 0) return 0;

    uint32_t oldMask = 0;
    for (int i = 0; i < 32; i++) if (CPU_ISSET(i, &oldSet)) oldMask |= 1u << i;
    return (jint)oldMask;
}
