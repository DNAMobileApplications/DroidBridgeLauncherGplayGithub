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
// Created by Vera-Firefly on 28.01.2025.
//
#ifndef BR_LOADER_H
#define BR_LOADER_H

void* load_symbol(void* handle, const char* symbol_name);
void* load_symbol_optional(void* handle, const char* symbol_name);
void* OSMGetProcAddress(void* handle, const char* symbol_name);
void* OSMGetProcAddressOptional(void* handle, const char* symbol_name);
void* GLGetProcAddress(void* handle, const char* symbol_name);

#endif //BR_LOADER_H