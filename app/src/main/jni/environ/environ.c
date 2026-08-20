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
// Created by maks on 24.09.2022.
//

#include <stdlib.h>
#include <android/log.h>
#include <assert.h>
#include <string.h>
#include "environ.h"
struct droidbridge_environ_s *droidbridge_environ;
__attribute__((constructor)) void env_init() {
    char* strptr_env = getenv("DROIDBRIDGE_ENVIRON");
    if(strptr_env == NULL) {
        __android_log_print(ANDROID_LOG_INFO, "Environ", "No environ found, creating...");
        droidbridge_environ = malloc(sizeof(struct droidbridge_environ_s));
        assert(droidbridge_environ);
        memset(droidbridge_environ, 0 , sizeof(struct droidbridge_environ_s));
        if(asprintf(&strptr_env, "%p", droidbridge_environ) == -1) abort();
        setenv("DROIDBRIDGE_ENVIRON", strptr_env, 1);
        free(strptr_env);
    }else{
        __android_log_print(ANDROID_LOG_INFO, "Environ", "Found existing environ: %s", strptr_env);
        droidbridge_environ = (void*) strtoul(strptr_env, NULL, 0x10);
    }
    __android_log_print(ANDROID_LOG_INFO, "Environ", "%p", droidbridge_environ);
}