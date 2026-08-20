#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

static pthread_mutex_t g_fps_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_frame_count = 0;
static int64_t g_sample_start_ns = 0;

static int64_t monotonic_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (int64_t) ts.tv_sec * 1000000000LL + (int64_t) ts.tv_nsec;
}

static void write_sample(int fps, int64_t elapsed_ms) {
    const char *path = getenv("DROIDBRIDGE_SDL3_FPS_FILE");
    if (path == NULL || path[0] == '\0') return;

    char temp_path[1024];
    if (snprintf(temp_path, sizeof(temp_path), "%s.tmp", path) <= 0) return;

    int fd = open(temp_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;

    char payload[64];
    int length = snprintf(payload, sizeof(payload), "%d %lld\n", fps, (long long) elapsed_ms);
    if (length > 0) {
        ssize_t ignored = write(fd, payload, (size_t) length);
        (void) ignored;
    }
    close(fd);
    rename(temp_path, path);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeMarkFramePresented(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    const int64_t now = monotonic_ns();
    if (now <= 0) return;

    pthread_mutex_lock(&g_fps_lock);
    if (g_sample_start_ns == 0) {
        g_sample_start_ns = now;
        g_frame_count = 1;
        pthread_mutex_unlock(&g_fps_lock);
        return;
    }

    g_frame_count++;
    const int64_t elapsed = now - g_sample_start_ns;
    if (elapsed >= 1000000000LL) {
        int fps = (int) ((g_frame_count * 1000000000LL + elapsed / 2) / elapsed);
        int64_t elapsed_ms = now / 1000000LL;
        g_frame_count = 0;
        g_sample_start_ns = now;
        pthread_mutex_unlock(&g_fps_lock);
        write_sample(fps, elapsed_ms);
        return;
    }
    pthread_mutex_unlock(&g_fps_lock);
}
