/*
 * DroidBridge WebRTC compatibility shim: libXau.so.6
 */
#include <stddef.h>
#include <stdlib.h>
#define EXPORT __attribute__((visibility("default")))
typedef struct { unsigned short family; unsigned short address_length; char* address; unsigned short number_length; char* number; unsigned short name_length; char* name; unsigned short data_length; char* data; } Xauth;
EXPORT Xauth* XauGetAuthByAddr(unsigned short family, unsigned short address_length, const char* address, unsigned short number_length, const char* number, unsigned short name_length, const char* name) { return NULL; }
EXPORT void XauDisposeAuth(Xauth* auth) { if (auth) free(auth); }
EXPORT int XauFileName(void) { return 0; }
