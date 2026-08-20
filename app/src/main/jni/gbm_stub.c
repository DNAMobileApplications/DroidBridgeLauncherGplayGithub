/*
 * DroidBridge WebRTC compatibility shim: libgbm.so.1
 */
#include <stddef.h>
#include <stdint.h>
#define EXPORT __attribute__((visibility("default")))
struct gbm_device {};
struct gbm_bo {};
struct gbm_surface {};
EXPORT struct gbm_device* gbm_create_device(int fd) { return NULL; }
EXPORT void gbm_device_destroy(struct gbm_device* gbm) {}
EXPORT int gbm_device_get_fd(struct gbm_device* gbm) { return -1; }
EXPORT const char* gbm_device_get_backend_name(struct gbm_device* gbm) { return "droidbridge-null"; }
EXPORT int gbm_device_is_format_supported(struct gbm_device* gbm, uint32_t format, uint32_t usage) { return 0; }
EXPORT struct gbm_surface* gbm_surface_create(struct gbm_device* gbm, uint32_t width, uint32_t height, uint32_t format, uint32_t flags) { return NULL; }
EXPORT void gbm_surface_destroy(struct gbm_surface* surface) {}
EXPORT struct gbm_bo* gbm_surface_lock_front_buffer(struct gbm_surface* surface) { return NULL; }
EXPORT void gbm_surface_release_buffer(struct gbm_surface* surface, struct gbm_bo* bo) {}
EXPORT uint32_t gbm_bo_get_width(struct gbm_bo* bo) { return 0; }
EXPORT uint32_t gbm_bo_get_height(struct gbm_bo* bo) { return 0; }
EXPORT uint32_t gbm_bo_get_stride(struct gbm_bo* bo) { return 0; }
EXPORT uint32_t gbm_bo_get_format(struct gbm_bo* bo) { return 0; }
EXPORT void gbm_bo_destroy(struct gbm_bo* bo) {}
