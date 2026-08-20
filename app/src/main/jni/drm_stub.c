/*
 * DroidBridge WebRTC compatibility shim: libdrm.so.2
 */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#define EXPORT __attribute__((visibility("default")))
EXPORT int drmAvailable(void) { return 0; }
EXPORT int drmOpen(const char* name, const char* busid) { return -1; }
EXPORT int drmClose(int fd) { return 0; }
EXPORT int drmGetMagic(int fd, void* magic) { return -1; }
EXPORT int drmAuthMagic(int fd, unsigned int magic) { return -1; }
EXPORT int drmIoctl(int fd, unsigned long request, void* arg) { return -1; }
EXPORT char* drmGetDeviceNameFromFd(int fd) { return NULL; }
EXPORT int drmGetDevice(int fd, void** device) { if (device) *device = NULL; return -1; }
EXPORT void drmFreeDevice(void** device) {}
EXPORT int drmGetDevices2(uint32_t flags, void** devices, int max_devices) { return 0; }
EXPORT void drmFreeDevices(void** devices, int count) {}
EXPORT int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int* prime_fd) { if (prime_fd) *prime_fd = -1; return -1; }
EXPORT int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t* handle) { if (handle) *handle = 0; return -1; }
