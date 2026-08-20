/*
 * DroidBridge WebRTC compatibility shim: libXdmcp.so.6
 */
#include <stddef.h>
#define EXPORT __attribute__((visibility("default")))
EXPORT int XdmcpWrap(void) { return 0; }
EXPORT int XdmcpUnwrap(void) { return 0; }
EXPORT int XdmcpWriteARRAY8(void) { return 0; }
EXPORT int XdmcpReadARRAY8(void) { return 0; }
