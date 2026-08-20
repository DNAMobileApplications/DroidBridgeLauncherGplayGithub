/*
 * DroidBridge Launcher native runtime bridge component.
 *
 * Original project:
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

//
// Modifile by Vera-Firefly on 28.08.2023.
//

#ifndef DROIDBRIDGE_RUNTIME_OSMESA_LOADER_H
#define DROIDBRIDGE_RUNTIME_OSMESA_LOADER_H

#include <GL/osmesa.h>

extern GLboolean (*OSMesaMakeCurrent_p) (OSMesaContext ctx, void *buffer, GLenum type,
                                         GLsizei width, GLsizei height);
extern OSMesaContext (*OSMesaGetCurrentContext_p) (void);
extern OSMesaContext  (*OSMesaCreateContext_p) (GLenum format, OSMesaContext sharelist);
extern OSMesaContext  (*OSMesaCreateContextAttribs_p) (const int *attribList, OSMesaContext sharelist);
extern OSMesaContext  (*OSMesaCreateContextExt_p) (GLenum format, GLint depthBits, GLint stencilBits, GLint accumBits, OSMesaContext sharelist);
extern void (*OSMesaDestroyContext_p) (OSMesaContext ctx);
extern void (*OSMesaFlushFrontbuffer_p) ();
extern void (*OSMesaPixelStore_p) ( GLint pname, GLint value );
extern GLubyte* (*glGetString_p) (GLenum name);
extern void (*glFinish_p) (void);
extern void (*glClearColor_p) (GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha);
extern void (*glClear_p) (GLbitfield mask);
extern void (*glReadPixels_p) (GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void * data);
extern void (*glReadBuffer_p) (GLenum mode);
extern void* (*OSMesaGetProcAddress_p)(const char* funcName);

void dlsym_OSMesa();
#endif //DROIDBRIDGE_RUNTIME_OSMESA_LOADER_H
