/*
 * DroidBridge app-local libdrm compatibility layer.
 *
 * Purpose:
 *   Some Android Mesa/DroidBridge builds keep DT_NEEDED libdrm.so even though the
 *   actual Android display path is EGL_ANDROID/native-window/Turnip. Android
 *   devices often do not expose a public libdrm with the full desktop libdrm
 *   symbol set inside the app linker namespace. A tiny WebRTC drm_stub.c is
 *   not enough because Mesa requires symbols such as drmGetDevice2 and
 *   drmCommandWriteRead at dlopen time.
 *
 * This file provides the libdrm symbols needed by the uploaded Freedreno Mesa
 * libEGL_mesa.so/libgallium_dri.so set. It is intentionally conservative:
 * fd/ioctl calls are passed through to the kernel when possible; device-query
 * helpers return best-effort Android-safe data instead of crashing.
 */

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef DRM_NODE_PRIMARY
#define DRM_NODE_PRIMARY 0
#endif
#ifndef DRM_NODE_CONTROL
#define DRM_NODE_CONTROL 1
#endif
#ifndef DRM_NODE_RENDER
#define DRM_NODE_RENDER 2
#endif
#ifndef DRM_NODE_MAX
#define DRM_NODE_MAX 3
#endif

#ifndef DRM_BUS_PLATFORM
#define DRM_BUS_PLATFORM 2
#endif

#ifndef DRM_IOCTL_BASE
#define DRM_IOCTL_BASE 'd'
#endif
#ifndef DRM_COMMAND_BASE
#define DRM_COMMAND_BASE 0x40
#endif

#ifndef _IOC_NRBITS
#define _IOC_NRBITS   8
#define _IOC_TYPEBITS 8
#define _IOC_SIZEBITS 14
#define _IOC_DIRBITS  2
#define _IOC_NRSHIFT      0
#define _IOC_TYPESHIFT    (_IOC_NRSHIFT + _IOC_NRBITS)
#define _IOC_SIZESHIFT    (_IOC_TYPESHIFT + _IOC_TYPEBITS)
#define _IOC_DIRSHIFT     (_IOC_SIZESHIFT + _IOC_SIZEBITS)
#define _IOC_NONE  0U
#define _IOC_WRITE 1U
#define _IOC_READ  2U
#define _IOC(dir,type,nr,size) \
    (((dir)  << _IOC_DIRSHIFT) | \
     ((type) << _IOC_TYPESHIFT) | \
     ((nr)   << _IOC_NRSHIFT) | \
     ((size) << _IOC_SIZESHIFT))
#endif

#define DRM_IOC_READWRITE (_IOC_READ | _IOC_WRITE)
#define DRM_IOC_WRITEONLY (_IOC_WRITE)

typedef struct _drmVersion {
    int version_major;
    int version_minor;
    int version_patchlevel;
    int name_len;
    char *name;
    int date_len;
    char *date;
    int desc_len;
    char *desc;
} drmVersion, *drmVersionPtr;

typedef struct _drmPlatformBusInfo {
    char *fullname;
} drmPlatformBusInfo;

typedef struct _drmPlatformDeviceInfo {
    char **compatible;
} drmPlatformDeviceInfo;

typedef struct _drmDevice {
    char **nodes;
    int available_nodes;
    int bustype;
    union {
        drmPlatformBusInfo platform;
        char padding[64];
    } businfo;
    union {
        drmPlatformDeviceInfo platform;
        char padding[128];
    } deviceinfo;
} drmDevice, *drmDevicePtr;

static int file_exists(const char *path) {
    struct stat st;
    return path != NULL && stat(path, &st) == 0;
}

static char *dup_or_null(const char *s) {
    return s ? strdup(s) : NULL;
}

static drmDevicePtr make_device(void) {
    drmDevicePtr dev = (drmDevicePtr) calloc(1, sizeof(drmDevice));
    if (!dev) return NULL;

    dev->nodes = (char **) calloc(DRM_NODE_MAX, sizeof(char *));
    if (!dev->nodes) {
        free(dev);
        return NULL;
    }

    dev->bustype = DRM_BUS_PLATFORM;
    dev->businfo.platform.fullname = dup_or_null("droidbridge-android-platform");

    dev->deviceinfo.platform.compatible = (char **) calloc(3, sizeof(char *));
    if (dev->deviceinfo.platform.compatible) {
        dev->deviceinfo.platform.compatible[0] = dup_or_null("droidbridge,mesa-android");
        dev->deviceinfo.platform.compatible[1] = dup_or_null("freedreno");
        dev->deviceinfo.platform.compatible[2] = NULL;
    }

    if (file_exists("/dev/dri/card0")) {
        dev->nodes[DRM_NODE_PRIMARY] = dup_or_null("/dev/dri/card0");
        dev->available_nodes |= (1 << DRM_NODE_PRIMARY);
    }
    if (file_exists("/dev/dri/renderD128")) {
        dev->nodes[DRM_NODE_RENDER] = dup_or_null("/dev/dri/renderD128");
        dev->available_nodes |= (1 << DRM_NODE_RENDER);
    }

    /* Most Android/KGSL devices do not expose /dev/dri. Mesa still only needs a
     * non-crashing device object for several Android loader probes.
     */
    return dev;
}

int drmGetDevice2(int fd, uint32_t flags, drmDevicePtr *device) {
    (void) fd;
    (void) flags;
    if (!device) {
        errno = EINVAL;
        return -EINVAL;
    }
    *device = make_device();
    if (!*device) {
        errno = ENOMEM;
        return -ENOMEM;
    }
    return 0;
}

int drmGetDevices2(uint32_t flags, drmDevicePtr devices[], int max_devices) {
    (void) flags;
    if (max_devices <= 0) return 0;
    if (!devices) {
        errno = EINVAL;
        return -EINVAL;
    }
    devices[0] = make_device();
    if (!devices[0]) {
        errno = ENOMEM;
        return -ENOMEM;
    }
    return 1;
}

void drmFreeDevice(drmDevicePtr *device) {
    if (!device || !*device) return;
    drmDevicePtr dev = *device;
    if (dev->nodes) {
        for (int i = 0; i < DRM_NODE_MAX; ++i) free(dev->nodes[i]);
        free(dev->nodes);
    }
    free(dev->businfo.platform.fullname);
    if (dev->deviceinfo.platform.compatible) {
        for (int i = 0; dev->deviceinfo.platform.compatible[i] != NULL; ++i) {
            free(dev->deviceinfo.platform.compatible[i]);
        }
        free(dev->deviceinfo.platform.compatible);
    }
    free(dev);
    *device = NULL;
}

void drmFreeDevices(drmDevicePtr devices[], int count) {
    if (!devices || count <= 0) return;
    for (int i = 0; i < count; ++i) {
        if (devices[i]) drmFreeDevice(&devices[i]);
    }
}

int drmDevicesEqual(drmDevicePtr a, drmDevicePtr b) {
    if (a == b) return 1;
    if (!a || !b) return 0;
    if (a->available_nodes != b->available_nodes) return 0;
    for (int i = 0; i < DRM_NODE_MAX; ++i) {
        const char *an = a->nodes ? a->nodes[i] : NULL;
        const char *bn = b->nodes ? b->nodes[i] : NULL;
        if ((an == NULL) != (bn == NULL)) return 0;
        if (an && strcmp(an, bn) != 0) return 0;
    }
    return 1;
}

drmVersionPtr drmGetVersion(int fd) {
    (void) fd;
    drmVersionPtr v = (drmVersionPtr) calloc(1, sizeof(drmVersion));
    if (!v) return NULL;
    v->version_major = 2;
    v->version_minor = 4;
    v->version_patchlevel = 120;
    v->name = dup_or_null("droidbridge-libdrm-compat");
    v->date = dup_or_null("20260527");
    v->desc = dup_or_null("DroidBridge Android libdrm compatibility layer");
    v->name_len = v->name ? (int) strlen(v->name) : 0;
    v->date_len = v->date ? (int) strlen(v->date) : 0;
    v->desc_len = v->desc ? (int) strlen(v->desc) : 0;
    return v;
}

void drmFreeVersion(drmVersionPtr v) {
    if (!v) return;
    free(v->name);
    free(v->date);
    free(v->desc);
    free(v);
}

char *drmGetPrimaryDeviceNameFromFd(int fd) {
    (void) fd;
    if (file_exists("/dev/dri/card0")) return dup_or_null("/dev/dri/card0");
    errno = ENODEV;
    return NULL;
}

char *drmGetRenderDeviceNameFromFd(int fd) {
    (void) fd;
    if (file_exists("/dev/dri/renderD128")) return dup_or_null("/dev/dri/renderD128");
    errno = ENODEV;
    return NULL;
}

int drmGetNodeTypeFromFd(int fd) {
    (void) fd;
    return -1;
}

int drmGetCap(int fd, uint64_t capability, uint64_t *value) {
    (void) fd;
    (void) capability;
    if (value) *value = 0;
    errno = EINVAL;
    return -EINVAL;
}

int drmIoctl(int fd, unsigned long request, void *arg) {
    int ret;
    do {
        ret = ioctl(fd, request, arg);
    } while (ret == -1 && errno == EINTR);
    return ret;
}

int drmCommandWriteRead(int fd, unsigned long drmCommandIndex, void *data, unsigned long size) {
    unsigned long request = _IOC(DRM_IOC_READWRITE, DRM_IOCTL_BASE,
                                 DRM_COMMAND_BASE + drmCommandIndex, size);
    return drmIoctl(fd, request, data);
}

int drmCommandWrite(int fd, unsigned long drmCommandIndex, void *data, unsigned long size) {
    unsigned long request = _IOC(DRM_IOC_WRITEONLY, DRM_IOCTL_BASE,
                                 DRM_COMMAND_BASE + drmCommandIndex, size);
    return drmIoctl(fd, request, data);
}

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd) {
    (void) fd;
    (void) handle;
    (void) flags;
    if (prime_fd) *prime_fd = -1;
    errno = ENOSYS;
    return -ENOSYS;
}

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle) {
    (void) fd;
    (void) prime_fd;
    if (handle) *handle = 0;
    errno = ENOSYS;
    return -ENOSYS;
}

int drmSyncobjDestroy(int fd, uint32_t handle) {
    (void) fd;
    (void) handle;
    return 0;
}

int drmSyncobjExportSyncFile(int fd, uint32_t handle, int *sync_file_fd) {
    (void) fd;
    (void) handle;
    if (sync_file_fd) *sync_file_fd = -1;
    errno = ENOSYS;
    return -ENOSYS;
}

int drmSyncobjImportSyncFile(int fd, uint32_t handle, int sync_file_fd) {
    (void) fd;
    (void) handle;
    (void) sync_file_fd;
    errno = ENOSYS;
    return -ENOSYS;
}

int drmSyncobjFDToHandle(int fd, int obj_fd, uint32_t *handle) {
    (void) fd;
    (void) obj_fd;
    if (handle) *handle = 0;
    errno = ENOSYS;
    return -ENOSYS;
}

int drmSyncobjReset(int fd, const uint32_t *handles, uint32_t handle_count) {
    (void) fd;
    (void) handles;
    (void) handle_count;
    return 0;
}
