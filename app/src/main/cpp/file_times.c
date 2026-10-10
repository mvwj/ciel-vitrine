#include <jni.h>
#include <errno.h>
#include <sys/stat.h>
#include <time.h>

JNIEXPORT jint JNICALL
Java_com_cielvitrine_app_NativeFileTimes_setModifiedNative(
        JNIEnv *env, jobject self, jint fd, jlong seconds, jint nanos) {
    (void)env; (void)self;
    if (fd < 0 || nanos < 0 || nanos >= 1000000000) return EINVAL;
    // Refuse dates outside the ABI's time_t range instead of changing them.
    const time_t native_seconds = (time_t)seconds;
    if ((jlong)native_seconds != seconds) return EOVERFLOW;
    struct stat observed;
    if (fstat(fd, &observed) != 0) return errno;
    if (!S_ISREG(observed.st_mode)) return EINVAL;
    const struct timespec times[2] = {
        { .tv_sec = 0, .tv_nsec = UTIME_OMIT },
        { .tv_sec = native_seconds, .tv_nsec = nanos }
    };
    return futimens(fd, times) == 0 ? 0 : errno;
}
