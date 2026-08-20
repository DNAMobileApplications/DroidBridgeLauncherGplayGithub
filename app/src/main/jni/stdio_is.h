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
// Created by maks on 15.01.2025.
//

#ifndef DROIDBRIDGE_RUNTIME_STDIO_IS_H
#define DROIDBRIDGE_RUNTIME_STDIO_IS_H

#include <stdbool.h>

_Noreturn void nominal_exit(int code, bool is_signal);

#endif //DROIDBRIDGE_RUNTIME_STDIO_IS_H
