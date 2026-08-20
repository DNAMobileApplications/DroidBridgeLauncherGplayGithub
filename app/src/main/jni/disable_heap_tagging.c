/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

/*
 * Forge/OpenJDK installer heap-tagging preloader.
 *
 * android:allowNativeHeapPointerTagging="false" applies to app processes
 * started through zygote, but the Forge installer is started as a child
 * executable through ProcessBuilder:
 *
 *   <runtime>/bin/java -jar forge-installer.jar
 *
 * That child process can still enable bionic tagged heap pointers and then
 * libjvm can abort with:
 *
 *   Pointer tag for 0x... was truncated
 *
 * LD_PRELOAD this tiny library into the child process. Its constructor
 * disables bionic heap tagging before OpenJDK does real work.
 */

#include <malloc.h>
#include <android/log.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif

#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif

#define LOG_TAG "DisableHeapTagging"

__attribute__((constructor))
static void disable_heap_tagging_for_child_process(void) {
#if defined(__aarch64__)
    int result = mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
    __android_log_print(
            result ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
            LOG_TAG,
            "mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, NONE) result=%d",
            result
    );
#else
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "heap tagging preloader not needed on this ABI");
#endif
}
