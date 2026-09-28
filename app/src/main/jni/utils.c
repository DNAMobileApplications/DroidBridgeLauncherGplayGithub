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

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <strings.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include "log.h"

#include "utils.h"

typedef int (*Main_Function_t)(int, char**);
typedef void (*android_update_LD_LIBRARY_PATH_t)(char*);

long shared_awt_surface;

char** convert_to_char_array(JNIEnv *env, jobjectArray jstringArray) {
    int num_rows = (*env)->GetArrayLength(env, jstringArray);
    char **cArray = (char **) malloc(num_rows * sizeof(char*));
    jstring row;

    for (int i = 0; i < num_rows; i++) {
        row = (jstring) (*env)->GetObjectArrayElement(env, jstringArray, i);
        cArray[i] = (char*)(*env)->GetStringUTFChars(env, row, 0);
    }

    return cArray;
}

jobjectArray convert_from_char_array(JNIEnv *env, char **charArray, int num_rows) {
    jobjectArray resultArr = (*env)->NewObjectArray(env, num_rows, (*env)->FindClass(env, "java/lang/String"), NULL);
    jstring row;

    for (int i = 0; i < num_rows; i++) {
        row = (jstring) (*env)->NewStringUTF(env, charArray[i]);
        (*env)->SetObjectArrayElement(env, resultArr, i, row);
    }

    return resultArr;
}

void free_char_array(JNIEnv *env, jobjectArray jstringArray, const char **charArray) {
    int num_rows = (*env)->GetArrayLength(env, jstringArray);
    jstring row;

    for (int i = 0; i < num_rows; i++) {
        row = (jstring) (*env)->GetObjectArrayElement(env, jstringArray, i);
        (*env)->ReleaseStringUTFChars(env, row, charArray[i]);
    }
}

jstring convertStringJVM(JNIEnv* srcEnv, JNIEnv* dstEnv, jstring srcStr) {
    if (srcStr == NULL) {
        return NULL;
    }

    const char* srcStrC = (*srcEnv)->GetStringUTFChars(srcEnv, srcStr, 0);
    jstring dstStr = (*dstEnv)->NewStringUTF(dstEnv, srcStrC);
    (*srcEnv)->ReleaseStringUTFChars(srcEnv, srcStr, srcStrC);
    return dstStr;
}

JNIEXPORT void JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_setupBridgeSurfaceAWT(JNIEnv *env, jclass clazz, jlong surface) {
    shared_awt_surface = surface;
}

JNIEXPORT jlong JNICALL Java_android_view_Surface_nativeGetBridgeSurfaceAWT(JNIEnv *env, jclass clazz) {
    return (jlong) shared_awt_surface;
}

JNIEXPORT jint JNICALL Java_android_os_OpenJDKNativeRegister_nativeRegisterNatives(JNIEnv *env, jclass clazz, jstring registerSymbol) {
    const char *register_symbol_c = (*env)->GetStringUTFChars(env, registerSymbol, 0);
    void *symbol = dlsym(RTLD_DEFAULT, register_symbol_c);
    if (symbol == NULL) {
        printf("dlsym %s failed: %s\n", register_symbol_c, dlerror());
        return -1;
    }

    int (*registerNativesForClass)(JNIEnv*) = symbol;
    int result = registerNativesForClass(env);
    (*env)->ReleaseStringUTFChars(env, registerSymbol, register_symbol_c);

    return (jint) result;
}

JNIEXPORT void JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_setLdLibraryPath(JNIEnv *env, jclass clazz, jstring ldLibraryPath) {
    // jclass exception_cls = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");

    android_update_LD_LIBRARY_PATH_t android_update_LD_LIBRARY_PATH;

    void *libdl_handle = dlopen("libdl.so", RTLD_LAZY);
    void *updateLdLibPath = dlsym(libdl_handle, "android_update_LD_LIBRARY_PATH");
    if (updateLdLibPath == NULL) {
        updateLdLibPath = dlsym(libdl_handle, "__loader_android_update_LD_LIBRARY_PATH");
        if (updateLdLibPath == NULL) {
            char *dl_error_c = dlerror();
            LOGE("Error getting symbol android_update_LD_LIBRARY_PATH: %s", dl_error_c);
            // (*env)->ThrowNew(env, exception_cls, dl_error_c);
        }
    }

    android_update_LD_LIBRARY_PATH = (android_update_LD_LIBRARY_PATH_t) updateLdLibPath;
    const char* ldLibPathUtf = (*env)->GetStringUTFChars(env, ldLibraryPath, 0);

    /*
     * Keep the normal Android linker update, but also mirror the value into
     * the process environment.  Some launcher paths later need to fall back to
     * parsing LD_LIBRARY_PATH manually, especially when VMLauncher is started
     * from a secondary activity for GUI installer jars.
     */
    if (android_update_LD_LIBRARY_PATH != NULL) {
        android_update_LD_LIBRARY_PATH(ldLibPathUtf);
    }
    setenv("LD_LIBRARY_PATH", ldLibPathUtf, 1);

    (*env)->ReleaseStringUTFChars(env, ldLibraryPath, ldLibPathUtf);
}

static int droidbridge_is_ltw_library_path(const char *path) {
    if (path == NULL || path[0] == '\0') return 0;
    const char *base = strrchr(path, '/');
    base = base != NULL ? base + 1 : path;
    return strcasecmp(base, "libltw.so") == 0;
}

static void droidbridge_publish_ltw_handle(const char *path, void *handle) {
    if (handle == NULL || !droidbridge_is_ltw_library_path(path)) return;

    /*
     * Android 10 keeps the app/ART and embedded-OpenJDK native loaders in
     * different linker namespaces. The SDL3 EGL shim cannot dlopen an LTW
     * copy staged under app-private files from its anonymous namespace, even
     * though this ART-side preload has already succeeded. A dlopen handle is
     * process-local, so publish the already-loaded handle for the SDL3 shim to
     * reuse with dlsym() instead of reopening the library across namespaces.
     */
    char handle_text[2 + sizeof(uintptr_t) * 2 + 1];
    snprintf(handle_text, sizeof(handle_text), "0x%llx",
             (unsigned long long)(uintptr_t)handle);
    setenv("DROIDBRIDGE_LTW_PRELOADED_HANDLE", handle_text, 1);
    setenv("DROIDBRIDGE_LTW_PRELOADED_PATH", path, 1);
    LOGD("published LTW preload handle=%s path=%s", handle_text, path);
}

JNIEXPORT jboolean JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_dlopen(JNIEnv *env, jclass clazz, jstring name) {
    const char *nameUtf = (*env)->GetStringUTFChars(env, name, 0);
    void* handle = dlopen(nameUtf, RTLD_GLOBAL | RTLD_LAZY);
    if (!handle) {
        LOGE("dlopen %s failed: %s", nameUtf, dlerror());
    } else {
        LOGD("dlopen %s success", nameUtf);
        droidbridge_publish_ltw_handle(nameUtf, handle);
    }
    (*env)->ReleaseStringUTFChars(env, name, nameUtf);
    return handle != NULL;
}

JNIEXPORT jint JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_chdir(JNIEnv *env, jclass clazz, jstring nameStr) {
    const char *name = (*env)->GetStringUTFChars(env, nameStr, NULL);
    int retval = chdir(name);
    (*env)->ReleaseStringUTFChars(env, nameStr, name);
    return retval;
}

JNIEXPORT jint JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_executeBinary(JNIEnv *env, jclass clazz, jobjectArray cmdArgs) {
    jclass exception_cls = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
    jstring execFile = (*env)->GetObjectArrayElement(env, cmdArgs, 0);

    char *exec_file_c = (char*) (*env)->GetStringUTFChars(env, execFile, 0);
    void *exec_binary_handle = dlopen(exec_file_c, RTLD_LAZY);

    // (*env)->ReleaseStringUTFChars(env, ldLibraryPath, ld_library_path_c);
    (*env)->ReleaseStringUTFChars(env, execFile, exec_file_c);

    char *exec_error_c = dlerror();
    if (exec_error_c != NULL) {
        LOGE("Error: %s", exec_error_c);
        (*env)->ThrowNew(env, exception_cls, exec_error_c);
        return -1;
    }

    Main_Function_t Main_Function;
    Main_Function = (Main_Function_t) dlsym(exec_binary_handle, "main");

    exec_error_c = dlerror();
    if (exec_error_c != NULL) {
        LOGE("Error: %s", exec_error_c);
        (*env)->ThrowNew(env, exception_cls, exec_error_c);
        return -1;
    }

    int cmd_argv = (*env)->GetArrayLength(env, cmdArgs);
    char **cmd_args_c = convert_to_char_array(env, cmdArgs);
    int result = Main_Function(cmd_argv, cmd_args_c);
    free_char_array(env, cmdArgs, cmd_args_c);
    return result;
}

// METHOD 2
/*
JNIEXPORT jint JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_executeForkedBinary(JNIEnv *env, jclass clazz, jobjectArray cmdArgs) {
	int x, status;
	x = fork();
	if (x > 0) {
		wait(&status);
	} else {
		execvpe();
	}
	return status;
}
*/

