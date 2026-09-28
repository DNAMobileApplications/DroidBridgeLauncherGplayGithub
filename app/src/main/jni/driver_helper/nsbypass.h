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

#ifndef LINKER_NSBYPASS_H
#define LINKER_NSBYPASS_H

#include <stdbool.h>

bool linker_ns_load(const char* lib_search_path);
void* linker_ns_dlopen(const char* name, int flag);
void* linker_ns_dlopen_unique(const char* tmpdir, const char* name, int flag);
void* linker_ns_dlopen_unique_named(const char* tmpdir, const char* name, const char* unique_soname, int flag);
void* linker_ns_dlopen_alias_file(const char* alias_dir, const char* name, const char* output_name, const char* unique_soname, int flag);

#endif //LINKER_NSBYPASS_H