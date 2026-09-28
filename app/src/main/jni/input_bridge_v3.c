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

/*
 * V3 input bridge implementation.
 *
 * Status:
 * - Active development
 * - Works with some bugs:
 *  + Modded versions gives broken stuff..
 *
 * 
 * - Implements glfwSetCursorPos() to handle grab camera pos correctly.
 */

#include <assert.h>
#include <dlfcn.h>
#include <jni.h>
#include <libgen.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdatomic.h>
#include <math.h>

#include "log.h"
#include "utils.h"
#include "environ/environ.h"
#include <stdint.h>
#include <stdbool.h>

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_FRAMEBUFFER_SIZE 1004
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007
#define EVENT_TYPE_WINDOW_SIZE 1008

#define BTA_GAMEPAD_BUTTON_COUNT 15
#define BTA_GAMEPAD_AXIS_COUNT 6

static volatile int bta_gamepad_present = 1;
static volatile int bta_gamepad_device_id = -1;
static char bta_gamepad_name[192] = "DroidBridge Android Controller";
static char bta_gamepad_guid[33] = "030000005e0400008e02000014010000";
static float bta_gamepad_axes[BTA_GAMEPAD_AXIS_COUNT] = {0.0f, 0.0f, 0.0f, 0.0f, -1.0f, -1.0f};
static unsigned char bta_gamepad_buttons[BTA_GAMEPAD_BUTTON_COUNT] = {0};
static unsigned char bta_gamepad_hat = 0;

static bool droidbridge_env_flag_enabled(const char *name) {
    const char *value = getenv(name);
    if (value == NULL || value[0] == '\0') return false;
    return strcmp(value, "1") == 0
            || strcasecmp(value, "true") == 0
            || strcasecmp(value, "yes") == 0
            || strcasecmp(value, "on") == 0;
}

static bool droidbridge_should_dispatch_cursor_warp(void) {
    // Cursor warps must remain silent while grabbed. Minecraft recentres the
    // cursor in grabbed mode and turning those warps into movement callbacks
    // recreates the camera-fling regression. Controllable's virtual mouse runs
    // in ungrabbed GUI mode, where desktop GLFW emits the expected callback.
    return droidbridge_environ != NULL
            && !droidbridge_environ->isGrabbing
            && droidbridge_env_flag_enabled("DROIDBRIDGE_GLFW_CURSOR_WARP_CALLBACKS");
}

static void bta_copy_jstring(JNIEnv *env, jstring src, char *dst, size_t dst_size, const char *fallback) {
    if (dst == NULL || dst_size == 0) return;
    const char *value = NULL;
    if (src != NULL) {
        value = (*env)->GetStringUTFChars(env, src, NULL);
    }
    if (value == NULL || value[0] == '\0') {
        value = fallback != NULL ? fallback : "DroidBridge Android Controller";
        snprintf(dst, dst_size, "%s", value);
    } else {
        snprintf(dst, dst_size, "%s", value);
    }
    if (src != NULL && value != NULL && value != fallback) {
        (*env)->ReleaseStringUTFChars(env, src, value);
    }
}

static void bta_set_identity(JNIEnv *env, jint deviceId, jstring name, jstring descriptor) {
    bta_gamepad_present = 1;
    bta_gamepad_device_id = deviceId;
    bta_copy_jstring(env, name, bta_gamepad_name, sizeof(bta_gamepad_name), "DroidBridge Android Controller");
    // Use a stable Xbox 360 SDL GUID so BTA's gamepad mapping layer accepts the controller.
    // The visible name still comes from the real Android InputDevice.
    snprintf(bta_gamepad_guid, sizeof(bta_gamepad_guid), "%s", "030000005e0400008e02000014010000");
    __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "identity id=%d name=%s guid=%s", deviceId, bta_gamepad_name, bta_gamepad_guid);
}

static float bta_clampf(float v) {
    if (isnan(v) || isinf(v)) return 0.0f;
    if (v < -1.0f) return -1.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

static float bta_trigger_to_glfw(float v) {
    if (isnan(v) || isinf(v)) v = 0.0f;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return (v * 2.0f) - 1.0f;
}

static int bta_android_key_to_gamepad_button(jint keyCode) {
    switch (keyCode) {
        case 96: return 0;   // A
        case 97: return 1;   // B
        case 99: return 2;   // X
        case 100: return 3;  // Y
        case 102: return 4;  // L1
        case 103: return 5;  // R1
        case 104: return -1; // L2 is an axis only; do not mirror it to LB/hotbar
        case 105: return -1; // R2 is an axis only; do not mirror it to RB/hotbar
        case 109: return 6;  // SELECT/BACK
        case 108: return 7;  // START
        case 110: return 8;  // MODE/GUIDE
        case 106: return 9;  // L3
        case 107: return 10; // R3
        case 19: return 11;  // DPAD_UP
        case 22: return 12;  // DPAD_RIGHT
        case 20: return 13;  // DPAD_DOWN
        case 21: return 14;  // DPAD_LEFT
        case 23: return 0;   // DPAD_CENTER -> A
        default: return -1;
    }
}

static void registerFunctions(JNIEnv *env);
static void registerBtaGamepadFunctions(JNIEnv *env);

/*
 * Android controller events originate in ART, while Minecraft and its GUI
 * classes live in the embedded OpenJDK VM. Launcher-side Java reflection can
 * therefore never see ControlsScreen. These helpers attach the Android input
 * thread to the runtime VM and inspect the real Minecraft screen there.
 */
static bool droidbridge_jni_clear_exception(JNIEnv *env) {
    if (env == NULL || !(*env)->ExceptionCheck(env)) return false;
    (*env)->ExceptionClear(env);
    return true;
}

static JNIEnv *droidbridge_get_runtime_env(void) {
    if (droidbridge_environ == NULL || droidbridge_environ->runtimeJavaVMPtr == NULL) {
        return NULL;
    }
    JavaVM *jvm = droidbridge_environ->runtimeJavaVMPtr;
    JNIEnv *env = NULL;
    jint result = (*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_4);
    if (result == JNI_EDETACHED) {
        /*
         * This call originates on an Android/native input helper thread, not a
         * Java thread owned by the embedded OpenJDK VM.  Keep it attached for
         * low-latency Minecraft screen/keybind probes, but make the attachment
         * daemon so it cannot keep DestroyJavaVM alive after Minecraft's main
         * thread has finished.
         */
        result = (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **) &env, NULL);
    }
    return result == JNI_OK ? env : NULL;
}

/*
 * The structural keybind detector may be queried after a menu click. Discovering
 * Minecraft through Thread.getAllStackTraces() is expensive enough to cause a
 * visible frame-time spike. Cache the confirmed live client instance as a JNI
 * global reference for the lifetime of the game process so later probes inspect
 * only the current GUI owner.
 */
static _Atomic(uintptr_t) droidbridge_cached_minecraft_instance_ref = 0;

static jobject droidbridge_get_cached_minecraft_instance(JNIEnv *env) {
    if (env == NULL) return NULL;
    uintptr_t raw = atomic_load_explicit(
            &droidbridge_cached_minecraft_instance_ref, memory_order_acquire);
    if (raw == 0) return NULL;
    jobject local = (*env)->NewLocalRef(env, (jobject) raw);
    if (local == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return NULL;
    }
    return local;
}

static void droidbridge_cache_minecraft_instance(JNIEnv *env, jobject instance) {
    if (env == NULL || instance == NULL) return;
    if (atomic_load_explicit(
            &droidbridge_cached_minecraft_instance_ref, memory_order_acquire) != 0) {
        return;
    }
    jobject global = (*env)->NewGlobalRef(env, instance);
    if (global == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return;
    }
    uintptr_t expected = 0;
    if (!atomic_compare_exchange_strong_explicit(
            &droidbridge_cached_minecraft_instance_ref,
            &expected,
            (uintptr_t) global,
            memory_order_release,
            memory_order_relaxed)) {
        (*env)->DeleteGlobalRef(env, global);
    }
}

static bool droidbridge_ascii_contains_ci(const char *value, const char *needle) {
    if (value == NULL || needle == NULL || needle[0] == '\0') return false;
    size_t value_len = strlen(value);
    size_t needle_len = strlen(needle);
    if (needle_len > value_len) return false;
    for (size_t i = 0; i + needle_len <= value_len; i++) {
        bool equal = true;
        for (size_t j = 0; j < needle_len; j++) {
            unsigned char a = (unsigned char) value[i + j];
            unsigned char b = (unsigned char) needle[j];
            if (a >= 'A' && a <= 'Z') a = (unsigned char) (a - 'A' + 'a');
            if (b >= 'A' && b <= 'Z') b = (unsigned char) (b - 'A' + 'a');
            if (a != b) {
                equal = false;
                break;
            }
        }
        if (equal) return true;
    }
    return false;
}

static bool droidbridge_ascii_ends_with_ci(const char *value, const char *suffix) {
    if (value == NULL || suffix == NULL) return false;
    size_t value_len = strlen(value);
    size_t suffix_len = strlen(suffix);
    if (suffix_len > value_len) return false;
    return droidbridge_ascii_contains_ci(value + value_len - suffix_len, suffix)
           && strlen(value + value_len - suffix_len) == suffix_len;
}

static bool droidbridge_copy_jstring(JNIEnv *env, jstring value, char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return false;
    out[0] = '\0';
    if (env == NULL || value == NULL) return false;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    snprintf(out, out_size, "%s", utf);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return out[0] != '\0';
}

static bool droidbridge_runtime_object_class_name(JNIEnv *env, jobject object, char *out, size_t out_size) {
    if (env == NULL || object == NULL || out == NULL || out_size == 0) return false;
    jclass object_class = (*env)->GetObjectClass(env, object);
    if (object_class == NULL || droidbridge_jni_clear_exception(env)) return false;
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (class_class == NULL || droidbridge_jni_clear_exception(env)) {
        (*env)->DeleteLocalRef(env, object_class);
        return false;
    }
    jmethodID get_name = (*env)->GetMethodID(env, class_class, "getName", "()Ljava/lang/String;");
    if (get_name == NULL || droidbridge_jni_clear_exception(env)) {
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, object_class);
        return false;
    }
    jstring name = (jstring) (*env)->CallObjectMethod(env, object_class, get_name);
    bool ok = !droidbridge_jni_clear_exception(env) && droidbridge_copy_jstring(env, name, out, out_size);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, object_class);
    return ok;
}

static jclass droidbridge_find_minecraft_class(JNIEnv *env) {
    if (env == NULL) return NULL;

    const char *direct_class_names[] = {
            "net/minecraft/client/Minecraft",
            "net/minecraft/client/MinecraftClient",
            "net/minecraft/class_310"
    };
    for (size_t i = 0; i < sizeof(direct_class_names) / sizeof(direct_class_names[0]); i++) {
        jclass direct = (*env)->FindClass(env, direct_class_names[i]);
        if (direct != NULL && !droidbridge_jni_clear_exception(env)) {
            return direct;
        }
        droidbridge_jni_clear_exception(env);
    }

    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
    jclass map_class = (*env)->FindClass(env, "java/util/Map");
    jclass set_class = (*env)->FindClass(env, "java/util/Set");
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (thread_class == NULL || map_class == NULL || set_class == NULL || class_class == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return NULL;
    }

    jmethodID get_all_stacks = (*env)->GetStaticMethodID(
            env, thread_class, "getAllStackTraces", "()Ljava/util/Map;");
    jmethodID get_context_loader = (*env)->GetMethodID(
            env, thread_class, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID key_set = (*env)->GetMethodID(env, map_class, "keySet", "()Ljava/util/Set;");
    jmethodID to_array = (*env)->GetMethodID(env, set_class, "toArray", "()[Ljava/lang/Object;");
    jmethodID for_name = (*env)->GetStaticMethodID(
            env, class_class, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (get_all_stacks == NULL || get_context_loader == NULL || key_set == NULL
            || to_array == NULL || for_name == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, set_class);
        (*env)->DeleteLocalRef(env, map_class);
        (*env)->DeleteLocalRef(env, thread_class);
        return NULL;
    }

    jobject stack_map = (*env)->CallStaticObjectMethod(env, thread_class, get_all_stacks);
    if (stack_map == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, set_class);
        (*env)->DeleteLocalRef(env, map_class);
        (*env)->DeleteLocalRef(env, thread_class);
        return NULL;
    }
    jobject threads_set = (*env)->CallObjectMethod(env, stack_map, key_set);
    jobjectArray threads = threads_set == NULL ? NULL
            : (jobjectArray) (*env)->CallObjectMethod(env, threads_set, to_array);
    droidbridge_jni_clear_exception(env);
    const char *minecraft_names[] = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310"
    };
    jstring minecraft_name_values[sizeof(minecraft_names) / sizeof(minecraft_names[0])];
    for (size_t n = 0; n < sizeof(minecraft_names) / sizeof(minecraft_names[0]); n++) {
        minecraft_name_values[n] = (*env)->NewStringUTF(env, minecraft_names[n]);
    }
    jclass found = NULL;

    if (threads != NULL) {
        jsize count = (*env)->GetArrayLength(env, threads);
        for (jsize i = 0; i < count && found == NULL; i++) {
            jobject thread = (*env)->GetObjectArrayElement(env, threads, i);
            if (thread == NULL || droidbridge_jni_clear_exception(env)) {
                droidbridge_jni_clear_exception(env);
                continue;
            }
            jobject loader = (*env)->CallObjectMethod(env, thread, get_context_loader);
            if (!droidbridge_jni_clear_exception(env) && loader != NULL) {
                for (size_t n = 0;
                     n < sizeof(minecraft_name_values) / sizeof(minecraft_name_values[0])
                             && found == NULL;
                     n++) {
                    if (minecraft_name_values[n] == NULL) continue;
                    jobject candidate = (*env)->CallStaticObjectMethod(
                            env, class_class, for_name,
                            minecraft_name_values[n], JNI_FALSE, loader);
                    if (!droidbridge_jni_clear_exception(env) && candidate != NULL) {
                        found = (jclass) candidate;
                    } else {
                        droidbridge_jni_clear_exception(env);
                    }
                }
            }
            if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
            (*env)->DeleteLocalRef(env, thread);
        }
    }

    for (size_t n = 0; n < sizeof(minecraft_name_values) / sizeof(minecraft_name_values[0]); n++) {
        if (minecraft_name_values[n] != NULL) {
            (*env)->DeleteLocalRef(env, minecraft_name_values[n]);
        }
    }
    if (threads != NULL) (*env)->DeleteLocalRef(env, threads);
    if (threads_set != NULL) (*env)->DeleteLocalRef(env, threads_set);
    (*env)->DeleteLocalRef(env, stack_map);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, set_class);
    (*env)->DeleteLocalRef(env, map_class);
    (*env)->DeleteLocalRef(env, thread_class);
    return found;
}

static jobject droidbridge_get_minecraft_instance(JNIEnv *env, jclass minecraft_class) {
    if (env == NULL || minecraft_class == NULL) return NULL;

    /*
     * Development/mapped jars expose getInstance()/instance, but production
     * Forge/Fabric/vanilla jars keep the Minecraft class name while obfuscating
     * its members (m_... / f_...). Try the readable members first, then resolve
     * the singleton by member TYPE instead of member NAME.
     */
    const char *method_names[] = {"getInstance", "getMinecraft"};
    for (size_t i = 0; i < sizeof(method_names) / sizeof(method_names[0]); i++) {
        jmethodID method = (*env)->GetStaticMethodID(
                env, minecraft_class, method_names[i], "()Lnet/minecraft/client/Minecraft;");
        if (method != NULL && !droidbridge_jni_clear_exception(env)) {
            jobject instance = (*env)->CallStaticObjectMethod(env, minecraft_class, method);
            if (!droidbridge_jni_clear_exception(env) && instance != NULL) return instance;
        }
        droidbridge_jni_clear_exception(env);
    }

    const char *field_names[] = {"instance", "theMinecraft", "minecraft"};
    for (size_t i = 0; i < sizeof(field_names) / sizeof(field_names[0]); i++) {
        jfieldID field = (*env)->GetStaticFieldID(
                env, minecraft_class, field_names[i], "Lnet/minecraft/client/Minecraft;");
        if (field != NULL && !droidbridge_jni_clear_exception(env)) {
            jobject instance = (*env)->GetStaticObjectField(env, minecraft_class, field);
            if (!droidbridge_jni_clear_exception(env) && instance != NULL) return instance;
        }
        droidbridge_jni_clear_exception(env);
    }

    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    jclass method_class = (*env)->FindClass(env, "java/lang/reflect/Method");
    if (class_class == NULL || field_class == NULL || method_class == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return NULL;
    }

    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID get_declared_methods = (*env)->GetMethodID(
            env, class_class, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");

    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    jmethodID field_set_accessible = (*env)->GetMethodID(
            env, field_class, "setAccessible", "(Z)V");
    jmethodID field_get = (*env)->GetMethodID(
            env, field_class, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");

    jmethodID method_get_return_type = (*env)->GetMethodID(
            env, method_class, "getReturnType", "()Ljava/lang/Class;");
    jmethodID method_get_parameter_types = (*env)->GetMethodID(
            env, method_class, "getParameterTypes", "()[Ljava/lang/Class;");
    jmethodID method_get_modifiers = (*env)->GetMethodID(
            env, method_class, "getModifiers", "()I");
    jmethodID method_set_accessible = (*env)->GetMethodID(
            env, method_class, "setAccessible", "(Z)V");
    jmethodID method_invoke = (*env)->GetMethodID(
            env, method_class, "invoke",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

    if (get_declared_fields == NULL || get_declared_methods == NULL || get_superclass == NULL
            || field_get_type == NULL || field_get_modifiers == NULL
            || field_set_accessible == NULL || field_get == NULL
            || method_get_return_type == NULL || method_get_parameter_types == NULL
            || method_get_modifiers == NULL || method_set_accessible == NULL
            || method_invoke == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, method_class);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        return NULL;
    }

    jobject instance = NULL;
    jobject current_class = minecraft_class;

    /* First preference: the static singleton field whose declared type is Minecraft. */
    while (current_class != NULL && instance == NULL) {
        jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                env, current_class, get_declared_fields);
        if (droidbridge_jni_clear_exception(env)) fields = NULL;
        if (fields != NULL) {
            jsize count = (*env)->GetArrayLength(env, fields);
            for (jsize i = 0; i < count && instance == NULL; i++) {
                jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                if (field == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                bool is_static = (modifiers & 0x0008) != 0;
                bool same_type = type != NULL && (*env)->IsSameObject(env, type, minecraft_class);
                droidbridge_jni_clear_exception(env);
                if (is_static && same_type) {
                    (*env)->CallVoidMethod(env, field, field_set_accessible, JNI_TRUE);
                    droidbridge_jni_clear_exception(env);
                    jobject value = (*env)->CallObjectMethod(env, field, field_get, NULL);
                    if (!droidbridge_jni_clear_exception(env) && value != NULL) {
                        instance = value;
                    }
                }
                if (type != NULL) (*env)->DeleteLocalRef(env, type);
                (*env)->DeleteLocalRef(env, field);
            }
            (*env)->DeleteLocalRef(env, fields);
        }
        if (instance != NULL) break;
        jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current_class != minecraft_class) (*env)->DeleteLocalRef(env, current_class);
        current_class = next;
    }
    if (current_class != NULL && current_class != minecraft_class) {
        (*env)->DeleteLocalRef(env, current_class);
    }

    /* Fallback: any static, zero-argument method returning Minecraft. */
    if (instance == NULL) {
        current_class = minecraft_class;
        while (current_class != NULL && instance == NULL) {
            jobjectArray methods = (jobjectArray) (*env)->CallObjectMethod(
                    env, current_class, get_declared_methods);
            if (droidbridge_jni_clear_exception(env)) methods = NULL;
            if (methods != NULL) {
                jsize count = (*env)->GetArrayLength(env, methods);
                for (jsize i = 0; i < count && instance == NULL; i++) {
                    jobject method = (*env)->GetObjectArrayElement(env, methods, i);
                    if (method == NULL || droidbridge_jni_clear_exception(env)) {
                        droidbridge_jni_clear_exception(env);
                        continue;
                    }
                    jint modifiers = (*env)->CallIntMethod(env, method, method_get_modifiers);
                    jobject return_type = (*env)->CallObjectMethod(env, method, method_get_return_type);
                    jobjectArray parameter_types = (jobjectArray) (*env)->CallObjectMethod(
                            env, method, method_get_parameter_types);
                    bool is_static = (modifiers & 0x0008) != 0;
                    bool same_return = return_type != NULL
                            && (*env)->IsSameObject(env, return_type, minecraft_class);
                    bool zero_args = parameter_types != NULL
                            && (*env)->GetArrayLength(env, parameter_types) == 0;
                    droidbridge_jni_clear_exception(env);
                    if (is_static && same_return && zero_args) {
                        (*env)->CallVoidMethod(env, method, method_set_accessible, JNI_TRUE);
                        droidbridge_jni_clear_exception(env);
                        jobject value = (*env)->CallObjectMethod(
                                env, method, method_invoke, NULL, NULL);
                        if (!droidbridge_jni_clear_exception(env) && value != NULL) {
                            instance = value;
                        }
                    }
                    if (parameter_types != NULL) (*env)->DeleteLocalRef(env, parameter_types);
                    if (return_type != NULL) (*env)->DeleteLocalRef(env, return_type);
                    (*env)->DeleteLocalRef(env, method);
                }
                (*env)->DeleteLocalRef(env, methods);
            }
            if (instance != NULL) break;
            jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
            if (droidbridge_jni_clear_exception(env)) next = NULL;
            if (current_class != minecraft_class) (*env)->DeleteLocalRef(env, current_class);
            current_class = next;
        }
        if (current_class != NULL && current_class != minecraft_class) {
            (*env)->DeleteLocalRef(env, current_class);
        }
    }

    if (instance != NULL) {
        __android_log_print(ANDROID_LOG_INFO, "DroidBridgeKeybind",
                            "resolved Minecraft singleton by reflected member type");
    }

    (*env)->DeleteLocalRef(env, method_class);
    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);
    return instance;
}

static bool droidbridge_reflect_class_name(
        JNIEnv *env, jobject class_object, char *out, size_t out_size) {
    if (out != NULL && out_size > 0) out[0] = '\0';
    if (env == NULL || class_object == NULL || out == NULL || out_size == 0) return false;
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (class_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    jmethodID get_name = (*env)->GetMethodID(
            env, class_class, "getName", "()Ljava/lang/String;");
    if (get_name == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        return false;
    }
    jstring name = (jstring) (*env)->CallObjectMethod(env, class_object, get_name);
    bool ok = !droidbridge_jni_clear_exception(env)
            && droidbridge_copy_jstring(env, name, out, out_size);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
    (*env)->DeleteLocalRef(env, class_class);
    return ok;
}

static bool droidbridge_type_name_is_numeric_primitive(const char *name) {
    return name != NULL && (strcmp(name, "int") == 0
            || strcmp(name, "long") == 0
            || strcmp(name, "short") == 0
            || strcmp(name, "byte") == 0);
}

/*
 * KeyMapping/KeyBinding names change between Mojang, Forge, Fabric,
 * NeoForge, old MCP and fully obfuscated vanilla jars. Their data shape does
 * not: a binding owns a translation/name String, a numeric press/key state and
 * a pressed boolean. Inspect only declared instance fields so GUI buttons
 * (which carry many coordinate integers) are not mistaken for key bindings.
 */
static bool droidbridge_class_looks_like_key_binding(JNIEnv *env, jobject candidate_class) {
    if (env == NULL || candidate_class == NULL) return false;

    char class_name[384];
    droidbridge_reflect_class_name(env, candidate_class, class_name, sizeof(class_name));
    if (droidbridge_ascii_ends_with_ci(class_name, ".KeyMapping")
            || droidbridge_ascii_ends_with_ci(class_name, ".KeyBinding")
            || droidbridge_ascii_ends_with_ci(class_name, "$KeyMapping")
            || droidbridge_ascii_ends_with_ci(class_name, "$KeyBinding")
            || droidbridge_ascii_ends_with_ci(class_name, ".class_304")) {
        return true;
    }

    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    if (class_class == NULL || field_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    if (get_declared_fields == NULL || field_get_type == NULL
            || field_get_modifiers == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        return false;
    }

    int string_fields = 0;
    int numeric_fields = 0;
    int boolean_fields = 0;
    int total_instance_fields = 0;
    jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
            env, candidate_class, get_declared_fields);
    if (droidbridge_jni_clear_exception(env)) fields = NULL;
    if (fields != NULL) {
        jsize count = (*env)->GetArrayLength(env, fields);
        for (jsize i = 0; i < count; i++) {
            jobject field = (*env)->GetObjectArrayElement(env, fields, i);
            if (field == NULL || droidbridge_jni_clear_exception(env)) {
                droidbridge_jni_clear_exception(env);
                continue;
            }
            jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
            jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
            droidbridge_jni_clear_exception(env);
            if ((modifiers & 0x0008) == 0 && type != NULL) {
                char type_name[192];
                droidbridge_reflect_class_name(env, type, type_name, sizeof(type_name));
                total_instance_fields++;
                if (strcmp(type_name, "java.lang.String") == 0) string_fields++;
                if (droidbridge_type_name_is_numeric_primitive(type_name)) numeric_fields++;
                if (strcmp(type_name, "boolean") == 0) boolean_fields++;
            }
            if (type != NULL) (*env)->DeleteLocalRef(env, type);
            (*env)->DeleteLocalRef(env, field);
        }
        (*env)->DeleteLocalRef(env, fields);
    }

    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);

    return string_fields >= 1 && string_fields <= 3
            && numeric_fields >= 1 && numeric_fields <= 4
            && boolean_fields >= 1 && boolean_fields <= 2
            && total_instance_fields >= 3 && total_instance_fields <= 12;
}

/*
 * Screen rendering and pointer method signatures survive every mapping system.
 * This distinguishes the active GUI object from GameOptions, renderers and
 * other Minecraft fields even when every class/member name is obfuscated.
 */
static bool droidbridge_class_looks_like_screen(JNIEnv *env, jobject candidate_class) {
    if (env == NULL || candidate_class == NULL) return false;

    char class_name[384];
    droidbridge_reflect_class_name(env, candidate_class, class_name, sizeof(class_name));
    if (droidbridge_ascii_ends_with_ci(class_name, ".Screen")
            || droidbridge_ascii_ends_with_ci(class_name, ".GuiScreen")
            || droidbridge_ascii_ends_with_ci(class_name, ".class_437")) {
        return true;
    }

    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass method_class = (*env)->FindClass(env, "java/lang/reflect/Method");
    if (class_class == NULL || method_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    jmethodID get_declared_methods = (*env)->GetMethodID(
            env, class_class, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    jmethodID method_get_return_type = (*env)->GetMethodID(
            env, method_class, "getReturnType", "()Ljava/lang/Class;");
    jmethodID method_get_parameter_types = (*env)->GetMethodID(
            env, method_class, "getParameterTypes", "()[Ljava/lang/Class;");
    jmethodID method_get_modifiers = (*env)->GetMethodID(
            env, method_class, "getModifiers", "()I");
    if (get_declared_methods == NULL || get_superclass == NULL
            || method_get_return_type == NULL || method_get_parameter_types == NULL
            || method_get_modifiers == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, method_class);
        (*env)->DeleteLocalRef(env, class_class);
        return false;
    }

    bool render_signature = false;
    bool pointer_signature = false;
    jobject current_class = candidate_class;
    int depth = 0;
    while (current_class != NULL && depth++ < 8 && !render_signature) {
        jobjectArray methods = (jobjectArray) (*env)->CallObjectMethod(
                env, current_class, get_declared_methods);
        if (droidbridge_jni_clear_exception(env)) methods = NULL;
        if (methods != NULL) {
            jsize count = (*env)->GetArrayLength(env, methods);
            for (jsize i = 0; i < count && !render_signature; i++) {
                jobject method = (*env)->GetObjectArrayElement(env, methods, i);
                if (method == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, method, method_get_modifiers);
                jobject return_type = (*env)->CallObjectMethod(env, method, method_get_return_type);
                jobjectArray parameters = (jobjectArray) (*env)->CallObjectMethod(
                        env, method, method_get_parameter_types);
                droidbridge_jni_clear_exception(env);
                if ((modifiers & 0x0008) == 0 && return_type != NULL && parameters != NULL) {
                    char return_name[96];
                    droidbridge_reflect_class_name(env, return_type, return_name, sizeof(return_name));
                    jsize parameter_count = (*env)->GetArrayLength(env, parameters);
                    char parameter_names[4][96];
                    memset(parameter_names, 0, sizeof(parameter_names));
                    for (jsize p = 0; p < parameter_count && p < 4; p++) {
                        jobject parameter = (*env)->GetObjectArrayElement(env, parameters, p);
                        if (parameter != NULL && !droidbridge_jni_clear_exception(env)) {
                            droidbridge_reflect_class_name(
                                    env, parameter, parameter_names[p], sizeof(parameter_names[p]));
                        } else {
                            droidbridge_jni_clear_exception(env);
                        }
                        if (parameter != NULL) (*env)->DeleteLocalRef(env, parameter);
                    }
                    if (strcmp(return_name, "void") == 0
                            && parameter_count == 3
                            && strcmp(parameter_names[0], "int") == 0
                            && strcmp(parameter_names[1], "int") == 0
                            && strcmp(parameter_names[2], "float") == 0) {
                        render_signature = true;
                    }
                    if (strcmp(return_name, "void") == 0
                            && parameter_count == 4
                            && strcmp(parameter_names[1], "int") == 0
                            && strcmp(parameter_names[2], "int") == 0
                            && strcmp(parameter_names[3], "float") == 0) {
                        render_signature = true;
                    }
                    if (strcmp(return_name, "boolean") == 0
                            && parameter_count == 3
                            && strcmp(parameter_names[0], "double") == 0
                            && strcmp(parameter_names[1], "double") == 0
                            && strcmp(parameter_names[2], "int") == 0) {
                        pointer_signature = true;
                    }
                }
                if (parameters != NULL) (*env)->DeleteLocalRef(env, parameters);
                if (return_type != NULL) (*env)->DeleteLocalRef(env, return_type);
                (*env)->DeleteLocalRef(env, method);
            }
            (*env)->DeleteLocalRef(env, methods);
        }
        if (render_signature) break;
        jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current_class != candidate_class) (*env)->DeleteLocalRef(env, current_class);
        current_class = next;
    }
    if (current_class != NULL && current_class != candidate_class) {
        (*env)->DeleteLocalRef(env, current_class);
    }
    (*env)->DeleteLocalRef(env, method_class);
    (*env)->DeleteLocalRef(env, class_class);
    return render_signature || pointer_signature;
}

typedef struct {
    bool found;
    jint count;
} DroidBridgeKeyBindingCollection;

static DroidBridgeKeyBindingCollection droidbridge_keybinding_collection_in_value(
        JNIEnv *env, jobject value) {
    DroidBridgeKeyBindingCollection result = {false, 0};
    if (env == NULL || value == NULL) return result;

    jclass value_class = (*env)->GetObjectClass(env, value);
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass array_class = (*env)->FindClass(env, "java/lang/reflect/Array");
    jclass collection_class = (*env)->FindClass(env, "java/util/Collection");
    if (value_class == NULL || class_class == NULL || array_class == NULL
            || collection_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return result;
    }
    jmethodID is_array = (*env)->GetMethodID(env, class_class, "isArray", "()Z");
    jmethodID get_component_type = (*env)->GetMethodID(
            env, class_class, "getComponentType", "()Ljava/lang/Class;");
    jmethodID array_length = (*env)->GetStaticMethodID(
            env, array_class, "getLength", "(Ljava/lang/Object;)I");
    jmethodID array_get = (*env)->GetStaticMethodID(
            env, array_class, "get", "(Ljava/lang/Object;I)Ljava/lang/Object;");
    jmethodID collection_to_array = (*env)->GetMethodID(
            env, collection_class, "toArray", "()[Ljava/lang/Object;");
    if (is_array == NULL || get_component_type == NULL || array_length == NULL
            || array_get == NULL || collection_to_array == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, collection_class);
        (*env)->DeleteLocalRef(env, array_class);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, value_class);
        return result;
    }

    if ((*env)->CallBooleanMethod(env, value_class, is_array) == JNI_TRUE
            && !droidbridge_jni_clear_exception(env)) {
        jobject component_type = (*env)->CallObjectMethod(env, value_class, get_component_type);
        if (!droidbridge_jni_clear_exception(env) && component_type != NULL
                && droidbridge_class_looks_like_key_binding(env, component_type)) {
            result.found = true;
        }
        if (component_type != NULL) (*env)->DeleteLocalRef(env, component_type);
        jint length = (*env)->CallStaticIntMethod(env, array_class, array_length, value);
        if (droidbridge_jni_clear_exception(env)) length = 0;
        jint sample = length < 64 ? length : 64;
        for (jint i = 0; i < sample && !result.found; i++) {
            jobject element = (*env)->CallStaticObjectMethod(env, array_class, array_get, value, i);
            if (!droidbridge_jni_clear_exception(env) && element != NULL) {
                jclass element_class = (*env)->GetObjectClass(env, element);
                if (element_class != NULL
                        && droidbridge_class_looks_like_key_binding(env, element_class)) {
                    result.found = true;
                }
                if (element_class != NULL) (*env)->DeleteLocalRef(env, element_class);
            } else {
                droidbridge_jni_clear_exception(env);
            }
            if (element != NULL) (*env)->DeleteLocalRef(env, element);
        }
        if (result.found) result.count = length;
    } else {
        droidbridge_jni_clear_exception(env);
        if ((*env)->IsInstanceOf(env, value, collection_class)) {
            jobjectArray elements = (jobjectArray) (*env)->CallObjectMethod(
                    env, value, collection_to_array);
            if (!droidbridge_jni_clear_exception(env) && elements != NULL) {
                jsize length = (*env)->GetArrayLength(env, elements);
                jsize sample = length < 64 ? length : 64;
                for (jsize i = 0; i < sample && !result.found; i++) {
                    jobject element = (*env)->GetObjectArrayElement(env, elements, i);
                    if (element != NULL && !droidbridge_jni_clear_exception(env)) {
                        jclass element_class = (*env)->GetObjectClass(env, element);
                        if (element_class != NULL
                                && droidbridge_class_looks_like_key_binding(env, element_class)) {
                            result.found = true;
                        }
                        if (element_class != NULL) (*env)->DeleteLocalRef(env, element_class);
                    } else {
                        droidbridge_jni_clear_exception(env);
                    }
                    if (element != NULL) (*env)->DeleteLocalRef(env, element);
                }
                if (result.found) result.count = length;
                (*env)->DeleteLocalRef(env, elements);
            } else {
                droidbridge_jni_clear_exception(env);
            }
        }
    }

    (*env)->DeleteLocalRef(env, collection_class);
    (*env)->DeleteLocalRef(env, array_class);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, value_class);
    return result;
}

static DroidBridgeKeyBindingCollection droidbridge_find_keybinding_collection(
        JNIEnv *env, jobject owner, int remaining_depth) {
    DroidBridgeKeyBindingCollection result = {false, 0};
    if (env == NULL || owner == NULL || remaining_depth < 0) return result;

    jclass owner_class = (*env)->GetObjectClass(env, owner);
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    if (owner_class == NULL || class_class == NULL || field_class == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return result;
    }
    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    jmethodID field_set_accessible = (*env)->GetMethodID(
            env, field_class, "setAccessible", "(Z)V");
    jmethodID field_get = (*env)->GetMethodID(
            env, field_class, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (get_declared_fields == NULL || get_superclass == NULL || field_get_type == NULL
            || field_get_modifiers == NULL || field_set_accessible == NULL
            || field_get == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, owner_class);
        return result;
    }

    jobject current_class = owner_class;
    int class_depth = 0;
    while (current_class != NULL && class_depth++ < 6 && !result.found) {
        jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                env, current_class, get_declared_fields);
        if (droidbridge_jni_clear_exception(env)) fields = NULL;
        if (fields != NULL) {
            jsize count = (*env)->GetArrayLength(env, fields);
            for (jsize i = 0; i < count && !result.found; i++) {
                jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                if (field == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                droidbridge_jni_clear_exception(env);
                if ((modifiers & 0x0008) == 0 && type != NULL) {
                    char type_name[384];
                    droidbridge_reflect_class_name(env, type, type_name, sizeof(type_name));
                    bool primitive_or_leaf = strcmp(type_name, "java.lang.String") == 0
                            || strcmp(type_name, "boolean") == 0
                            || droidbridge_type_name_is_numeric_primitive(type_name)
                            || strcmp(type_name, "float") == 0
                            || strcmp(type_name, "double") == 0
                            || droidbridge_ascii_contains_ci(type_name, "java.lang.Class");
                    if (!primitive_or_leaf) {
                        (*env)->CallVoidMethod(env, field, field_set_accessible, JNI_TRUE);
                        droidbridge_jni_clear_exception(env);
                        jobject value = (*env)->CallObjectMethod(env, field, field_get, owner);
                        if (!droidbridge_jni_clear_exception(env) && value != NULL) {
                            result = droidbridge_keybinding_collection_in_value(env, value);
                            if (!result.found && remaining_depth > 0) {
                                jclass value_class = (*env)->GetObjectClass(env, value);
                                bool nested_is_screen = value_class != NULL
                                        && droidbridge_class_looks_like_screen(env, value_class);
                                if (value_class != NULL) (*env)->DeleteLocalRef(env, value_class);
                                if (!nested_is_screen) {
                                    result = droidbridge_find_keybinding_collection(
                                            env, value, remaining_depth - 1);
                                }
                            }
                        } else {
                            droidbridge_jni_clear_exception(env);
                        }
                        if (value != NULL) (*env)->DeleteLocalRef(env, value);
                    }
                }
                if (type != NULL) (*env)->DeleteLocalRef(env, type);
                (*env)->DeleteLocalRef(env, field);
            }
            (*env)->DeleteLocalRef(env, fields);
        }
        if (result.found) break;
        jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current_class != owner_class) (*env)->DeleteLocalRef(env, current_class);
        current_class = next;
    }
    if (current_class != NULL && current_class != owner_class) {
        (*env)->DeleteLocalRef(env, current_class);
    }
    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, owner_class);
    return result;
}

typedef struct {
    bool controls;
    bool capture;
} DroidBridgeKeybindScreenState;

static DroidBridgeKeybindScreenState droidbridge_reflect_keybind_screen_state(
        JNIEnv *env, jobject screen) {
    DroidBridgeKeybindScreenState state = {false, false};
    if (env == NULL || screen == NULL) return state;

    jclass screen_class = (*env)->GetObjectClass(env, screen);
    if (screen_class == NULL || droidbridge_jni_clear_exception(env)) return state;
    char screen_name[384];
    droidbridge_reflect_class_name(env, screen_class, screen_name, sizeof(screen_name));
    bool named_controls = droidbridge_ascii_contains_ci(screen_name, "ControlsScreen")
            || droidbridge_ascii_contains_ci(screen_name, "GuiControls")
            || droidbridge_ascii_contains_ci(screen_name, "KeyBindsScreen")
            || droidbridge_ascii_contains_ci(screen_name, "KeyMapping")
            || droidbridge_ascii_contains_ci(screen_name, "ControlsOptions")
            || droidbridge_ascii_ends_with_ci(screen_name, ".class_6599")
            || droidbridge_ascii_ends_with_ci(screen_name, ".class_458");
    bool screen_shape = droidbridge_class_looks_like_screen(env, screen_class);
    if (!screen_shape && !named_controls) {
        (*env)->DeleteLocalRef(env, screen_class);
        return state;
    }

    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    jclass boolean_class = (*env)->FindClass(env, "java/lang/Boolean");
    jclass number_class = (*env)->FindClass(env, "java/lang/Number");
    if (class_class == NULL || field_class == NULL || boolean_class == NULL
            || number_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, screen_class);
        return state;
    }
    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    jmethodID field_get_name = (*env)->GetMethodID(
            env, field_class, "getName", "()Ljava/lang/String;");
    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    jmethodID field_set_accessible = (*env)->GetMethodID(
            env, field_class, "setAccessible", "(Z)V");
    jmethodID field_get = (*env)->GetMethodID(
            env, field_class, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    jmethodID boolean_value = (*env)->GetMethodID(env, boolean_class, "booleanValue", "()Z");
    jmethodID int_value = (*env)->GetMethodID(env, number_class, "intValue", "()I");
    if (get_declared_fields == NULL || get_superclass == NULL || field_get_name == NULL
            || field_get_type == NULL || field_get_modifiers == NULL
            || field_set_accessible == NULL || field_get == NULL
            || boolean_value == NULL || int_value == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, number_class);
        (*env)->DeleteLocalRef(env, boolean_class);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, screen_class);
        return state;
    }

    bool declared_binding_slot = false;
    bool named_capture_seen = false;
    jobject current_class = screen_class;
    int depth = 0;
    while (current_class != NULL && depth++ < 6 && !state.capture) {
        jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                env, current_class, get_declared_fields);
        if (droidbridge_jni_clear_exception(env)) fields = NULL;
        if (fields != NULL) {
            jsize count = (*env)->GetArrayLength(env, fields);
            for (jsize i = 0; i < count && !state.capture; i++) {
                jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                if (field == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                jstring name_value = (jstring) (*env)->CallObjectMethod(env, field, field_get_name);
                jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                droidbridge_jni_clear_exception(env);
                char field_name[160];
                char type_name[384];
                droidbridge_copy_jstring(env, name_value, field_name, sizeof(field_name));
                droidbridge_reflect_class_name(env, type, type_name, sizeof(type_name));
                bool is_static = (modifiers & 0x0008) != 0;
                bool type_is_binding = !is_static && type != NULL
                        && droidbridge_class_looks_like_key_binding(env, type);
                bool named_binding_slot = droidbridge_ascii_contains_ci(field_name, "selectedKey")
                        || droidbridge_ascii_contains_ci(field_name, "chosenKey")
                        || droidbridge_ascii_contains_ci(field_name, "selectedMapping")
                        || droidbridge_ascii_contains_ci(field_name, "selectedBinding")
                        || droidbridge_ascii_contains_ci(field_name, "focusedBinding")
                        || strcasecmp(field_name, "field_34799") == 0;
                bool named_index = strcasecmp(field_name, "buttonId") == 0
                        || droidbridge_ascii_contains_ci(field_name, "selectedButton")
                        || droidbridge_ascii_contains_ci(field_name, "selectedKeyIndex");
                bool named_boolean = droidbridge_ascii_contains_ci(field_name, "capture")
                        || droidbridge_ascii_contains_ci(field_name, "listening")
                        || droidbridge_ascii_contains_ci(field_name, "rebinding")
                        || droidbridge_ascii_contains_ci(field_name, "awaitingKey");
                if (!is_static && (type_is_binding || named_binding_slot
                        || named_index || named_boolean)) {
                    if (type_is_binding || named_binding_slot) declared_binding_slot = true;
                    if (named_index || named_boolean) named_capture_seen = true;
                    (*env)->CallVoidMethod(env, field, field_set_accessible, JNI_TRUE);
                    droidbridge_jni_clear_exception(env);
                    jobject value = (*env)->CallObjectMethod(env, field, field_get, screen);
                    if (!droidbridge_jni_clear_exception(env) && value != NULL) {
                        if ((*env)->IsInstanceOf(env, value, boolean_class)) {
                            state.capture = named_boolean
                                    && (*env)->CallBooleanMethod(env, value, boolean_value) == JNI_TRUE;
                        } else if ((*env)->IsInstanceOf(env, value, number_class)) {
                            state.capture = named_index
                                    && (*env)->CallIntMethod(env, value, int_value) >= 0;
                        } else {
                            state.capture = type_is_binding || named_binding_slot;
                        }
                        droidbridge_jni_clear_exception(env);
                    } else {
                        droidbridge_jni_clear_exception(env);
                    }
                    if (value != NULL) (*env)->DeleteLocalRef(env, value);
                }
                if (type != NULL) (*env)->DeleteLocalRef(env, type);
                if (name_value != NULL) (*env)->DeleteLocalRef(env, name_value);
                (*env)->DeleteLocalRef(env, field);
            }
            (*env)->DeleteLocalRef(env, fields);
        }
        if (state.capture) break;
        jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current_class != screen_class) (*env)->DeleteLocalRef(env, current_class);
        current_class = next;
    }
    if (current_class != NULL && current_class != screen_class) {
        (*env)->DeleteLocalRef(env, current_class);
    }

    DroidBridgeKeyBindingCollection collection =
            droidbridge_find_keybinding_collection(env, screen, 1);

    int legacy_direct_int_fields = 0;
    jint legacy_only_int_value = -1;
    if (!declared_binding_slot && collection.found && collection.count > 0) {
        jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                env, screen_class, get_declared_fields);
        if (droidbridge_jni_clear_exception(env)) fields = NULL;
        if (fields != NULL) {
            jsize count = (*env)->GetArrayLength(env, fields);
            for (jsize i = 0; i < count; i++) {
                jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                if (field == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                char type_name[96];
                droidbridge_reflect_class_name(env, type, type_name, sizeof(type_name));
                droidbridge_jni_clear_exception(env);
                if ((modifiers & 0x0008) == 0 && strcmp(type_name, "int") == 0) {
                    legacy_direct_int_fields++;
                    (*env)->CallVoidMethod(env, field, field_set_accessible, JNI_TRUE);
                    droidbridge_jni_clear_exception(env);
                    jobject value = (*env)->CallObjectMethod(env, field, field_get, screen);
                    if (!droidbridge_jni_clear_exception(env) && value != NULL
                            && (*env)->IsInstanceOf(env, value, number_class)) {
                        legacy_only_int_value = (*env)->CallIntMethod(env, value, int_value);
                        droidbridge_jni_clear_exception(env);
                    } else {
                        droidbridge_jni_clear_exception(env);
                    }
                    if (value != NULL) (*env)->DeleteLocalRef(env, value);
                }
                if (type != NULL) (*env)->DeleteLocalRef(env, type);
                (*env)->DeleteLocalRef(env, field);
            }
            (*env)->DeleteLocalRef(env, fields);
        }
    }

    bool legacy_controls_shape = collection.found && collection.count > 0
            && legacy_direct_int_fields == 1;
    state.controls = named_controls || (screen_shape
            && (declared_binding_slot || named_capture_seen || legacy_controls_shape));

    /*
     * Minecraft 1.0-era GuiControls stores the active row as an obfuscated int
     * and keeps the KeyBinding[] inside GameSettings. The one-int screen shape
     * distinguishes it from unrelated option pages that also hold GameSettings.
     */
    if (!state.capture && state.controls && !declared_binding_slot
            && legacy_controls_shape) {
        state.capture = legacy_only_int_value >= 0
                && legacy_only_int_value < collection.count;
    }

    (*env)->DeleteLocalRef(env, number_class);
    (*env)->DeleteLocalRef(env, boolean_class);
    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, screen_class);
    return state;
}

/*
 * Prefer declared Screen/GuiScreen/class_437 types over broad method-shape
 * heuristics. The latter are retained only as an obfuscated-vanilla fallback.
 */
static bool droidbridge_class_is_known_screen_type(JNIEnv *env, jobject candidate_class) {
    if (env == NULL || candidate_class == NULL) return false;
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (class_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    if (get_superclass == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        return false;
    }

    bool known = false;
    jobject current = candidate_class;
    int depth = 0;
    while (current != NULL && depth++ < 12 && !known) {
        char name[384];
        droidbridge_reflect_class_name(env, current, name, sizeof(name));
        known = droidbridge_ascii_ends_with_ci(name, ".Screen")
                || droidbridge_ascii_ends_with_ci(name, ".GuiScreen")
                || droidbridge_ascii_ends_with_ci(name, ".class_437");
        if (known) break;
        jobject next = (*env)->CallObjectMethod(env, current, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current != candidate_class) (*env)->DeleteLocalRef(env, current);
        current = next;
    }
    if (current != NULL && current != candidate_class) (*env)->DeleteLocalRef(env, current);
    (*env)->DeleteLocalRef(env, class_class);
    return known;
}

static bool droidbridge_screen_matches_keybind_state(
        JNIEnv *env, jobject candidate, bool require_capture) {
    if (env == NULL || candidate == NULL) return false;
    jclass candidate_class = (*env)->GetObjectClass(env, candidate);
    if (candidate_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    bool screen_shape = droidbridge_class_is_known_screen_type(env, candidate_class)
            || droidbridge_class_looks_like_screen(env, candidate_class);
    (*env)->DeleteLocalRef(env, candidate_class);
    if (!screen_shape) return false;
    DroidBridgeKeybindScreenState state =
            droidbridge_reflect_keybind_screen_state(env, candidate);
    return require_capture ? state.capture : state.controls;
}

static jobject droidbridge_try_named_screen_getter(
        JNIEnv *env, jobject owner, bool require_capture) {
    if (env == NULL || owner == NULL) return NULL;
    jclass owner_class = (*env)->GetObjectClass(env, owner);
    if (owner_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return NULL;
    }
    const char *method_names[] = {"screen", "getScreen", "getCurrentScreen"};
    const char *method_signatures[] = {
            "()Lnet/minecraft/client/gui/screens/Screen;",
            "()Lnet/minecraft/client/gui/screen/Screen;",
            "()Lnet/minecraft/client/gui/GuiScreen;",
            "()Lnet/minecraft/class_437;"
    };
    jobject found = NULL;
    for (size_t n = 0; n < sizeof(method_names) / sizeof(method_names[0]) && found == NULL; n++) {
        for (size_t s = 0; s < sizeof(method_signatures) / sizeof(method_signatures[0])
                && found == NULL; s++) {
            jmethodID method = (*env)->GetMethodID(
                    env, owner_class, method_names[n], method_signatures[s]);
            if (method != NULL && !droidbridge_jni_clear_exception(env)) {
                jobject value = (*env)->CallObjectMethod(env, owner, method);
                if (!droidbridge_jni_clear_exception(env) && value != NULL
                        && droidbridge_screen_matches_keybind_state(
                                env, value, require_capture)) {
                    found = value;
                    value = NULL;
                } else {
                    droidbridge_jni_clear_exception(env);
                }
                if (value != NULL) (*env)->DeleteLocalRef(env, value);
            } else {
                droidbridge_jni_clear_exception(env);
            }
        }
    }
    (*env)->DeleteLocalRef(env, owner_class);
    return found;
}

static bool droidbridge_field_name_is_screen(const char *name) {
    return name != NULL && (strcasecmp(name, "screen") == 0
            || strcasecmp(name, "currentScreen") == 0
            || strcasecmp(name, "field_1755") == 0
            || droidbridge_ascii_contains_ci(name, "activeScreen"));
}


static bool droidbridge_class_declares_known_screen_field(
        JNIEnv *env, jobject candidate_class) {
    if (env == NULL || candidate_class == NULL) return false;
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    if (class_class == NULL || field_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    if (get_declared_fields == NULL || get_superclass == NULL
            || field_get_type == NULL || field_get_modifiers == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        return false;
    }

    bool found = false;
    jobject current = candidate_class;
    int depth = 0;
    while (current != NULL && depth++ < 5 && !found) {
        jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                env, current, get_declared_fields);
        if (droidbridge_jni_clear_exception(env)) fields = NULL;
        if (fields != NULL) {
            jsize count = (*env)->GetArrayLength(env, fields);
            if (count > 128) count = 128;
            for (jsize i = 0; i < count && !found; i++) {
                jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                if (field == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                droidbridge_jni_clear_exception(env);
                found = (modifiers & 0x0008) == 0 && type != NULL
                        && droidbridge_class_is_known_screen_type(env, type);
                if (type != NULL) (*env)->DeleteLocalRef(env, type);
                (*env)->DeleteLocalRef(env, field);
            }
            (*env)->DeleteLocalRef(env, fields);
        }
        if (found) break;
        jobject next = (*env)->CallObjectMethod(env, current, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current != candidate_class) (*env)->DeleteLocalRef(env, current);
        current = next;
    }
    if (current != NULL && current != candidate_class) (*env)->DeleteLocalRef(env, current);
    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);
    return found;
}

static bool droidbridge_field_looks_like_gui_holder(
        const char *field_name, const char *type_name) {
    return (field_name != NULL && (strcasecmp(field_name, "gui") == 0
            || strcasecmp(field_name, "hud") == 0
            || droidbridge_ascii_contains_ci(field_name, "screenManager")
            || droidbridge_ascii_contains_ci(field_name, "guiManager")))
            || (type_name != NULL && (droidbridge_ascii_ends_with_ci(type_name, ".Gui")
            || droidbridge_ascii_ends_with_ci(type_name, ".Hud")
            || droidbridge_ascii_ends_with_ci(type_name, ".InGameHud")
            || droidbridge_ascii_contains_ci(type_name, "ScreenManager")
            || droidbridge_ascii_contains_ci(type_name, "GuiManager")));
}

/*
 * Locate the actual keybind screen rather than returning the first object that
 * merely has Screen-like methods. This handles all three ownership layouts:
 *
 *   legacy/GLFW: Minecraft -> Screen
 *   26.2+/SDL3:  Minecraft -> Gui -> Screen
 *   obfuscated:  owner -> unknown holder -> structurally verified keybind Screen
 *
 * The search is shallow and budgeted. Every candidate must independently pass
 * the keybind controls/capture-state check before it can alter controller routing.
 */
static jobject droidbridge_reflect_find_matching_keybind_screen(
        JNIEnv *env, jobject owner, bool require_capture,
        int remaining_depth, int *remaining_budget) {
    if (env == NULL || owner == NULL || remaining_budget == NULL
            || *remaining_budget <= 0) return NULL;

    jobject getter_screen = droidbridge_try_named_screen_getter(
            env, owner, require_capture);
    if (getter_screen != NULL) return getter_screen;

    jclass owner_class = (*env)->GetObjectClass(env, owner);
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    if (owner_class == NULL || class_class == NULL || field_class == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return NULL;
    }
    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    jmethodID is_primitive = (*env)->GetMethodID(env, class_class, "isPrimitive", "()Z");
    jmethodID field_get_name = (*env)->GetMethodID(
            env, field_class, "getName", "()Ljava/lang/String;");
    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    jmethodID field_set_accessible = (*env)->GetMethodID(
            env, field_class, "setAccessible", "(Z)V");
    jmethodID field_get = (*env)->GetMethodID(
            env, field_class, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (get_declared_fields == NULL || get_superclass == NULL || is_primitive == NULL
            || field_get_name == NULL || field_get_type == NULL
            || field_get_modifiers == NULL || field_set_accessible == NULL
            || field_get == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, owner_class);
        return NULL;
    }

    jobject found = NULL;
    /*
     * Pass 0: named/declared Screen fields.
     * Pass 1: named or structurally identified Gui/Hud holders.
     * Pass 2: other live values that structurally are Screens.
     * Pass 3: generic one-level holders for fully obfuscated 26.2+ jars.
     */
    for (int pass = 0; pass < 4 && found == NULL; pass++) {
        if ((pass == 1 || pass == 3) && remaining_depth <= 0) continue;
        jobject current_class = owner_class;
        int class_depth = 0;
        while (current_class != NULL && class_depth++ < 8 && found == NULL
                && *remaining_budget > 0) {
            jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                    env, current_class, get_declared_fields);
            if (droidbridge_jni_clear_exception(env)) fields = NULL;
            if (fields != NULL) {
                jsize count = (*env)->GetArrayLength(env, fields);
                if (count > (remaining_depth > 0 ? 320 : 96)) {
                    count = remaining_depth > 0 ? 320 : 96;
                }
                for (jsize i = 0; i < count && found == NULL
                        && *remaining_budget > 0; i++) {
                    jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                    if (field == NULL || droidbridge_jni_clear_exception(env)) {
                        droidbridge_jni_clear_exception(env);
                        continue;
                    }
                    jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                    jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                    jstring name_value = (jstring) (*env)->CallObjectMethod(
                            env, field, field_get_name);
                    droidbridge_jni_clear_exception(env);
                    char field_name[192];
                    char type_name[384];
                    droidbridge_copy_jstring(env, name_value, field_name, sizeof(field_name));
                    droidbridge_reflect_class_name(env, type, type_name, sizeof(type_name));
                    bool static_field = (modifiers & 0x0008) != 0;
                    bool primitive = type != NULL
                            && (*env)->CallBooleanMethod(env, type, is_primitive) == JNI_TRUE;
                    droidbridge_jni_clear_exception(env);
                    bool strict_screen_field = type != NULL
                            && droidbridge_class_is_known_screen_type(env, type);
                    bool screen_named = droidbridge_field_name_is_screen(field_name);
                    bool holder_named = droidbridge_field_looks_like_gui_holder(
                            field_name, type_name);
                    bool holder_structural = pass == 1 && !static_field && !primitive
                            && type != NULL
                            && droidbridge_class_declares_known_screen_field(env, type);
                    bool inspect = !static_field && !primitive && (
                            (pass == 0 && (strict_screen_field || screen_named))
                            || (pass == 1 && (holder_named || holder_structural))
                            || pass == 2
                            || pass == 3);
                    if (inspect) {
                        if (pass != 2) (*remaining_budget)--;
                        (*env)->CallVoidMethod(env, field, field_set_accessible, JNI_TRUE);
                        droidbridge_jni_clear_exception(env);
                        jobject value = (*env)->CallObjectMethod(env, field, field_get, owner);
                        if (!droidbridge_jni_clear_exception(env) && value != NULL
                                && !(*env)->IsSameObject(env, value, owner)) {
                            jclass value_class = (*env)->GetObjectClass(env, value);
                            bool strict_value = value_class != NULL
                                    && droidbridge_class_is_known_screen_type(env, value_class);
                            bool broad_value = value_class != NULL
                                    && droidbridge_class_looks_like_screen(env, value_class);
                            if ((strict_value || (pass == 2 && broad_value))
                                    && droidbridge_screen_matches_keybind_state(
                                            env, value, require_capture)) {
                                found = value;
                                value = NULL;
                            } else if (pass >= 2 && remaining_depth > 0) {
                                found = droidbridge_reflect_find_matching_keybind_screen(
                                        env, value, require_capture,
                                        remaining_depth - 1, remaining_budget);
                            }
                            if (value_class != NULL) (*env)->DeleteLocalRef(env, value_class);
                        } else {
                            droidbridge_jni_clear_exception(env);
                        }
                        if (value != NULL) (*env)->DeleteLocalRef(env, value);
                    }
                    if (name_value != NULL) (*env)->DeleteLocalRef(env, name_value);
                    if (type != NULL) (*env)->DeleteLocalRef(env, type);
                    (*env)->DeleteLocalRef(env, field);
                }
                (*env)->DeleteLocalRef(env, fields);
            }
            if (found != NULL) break;
            jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
            if (droidbridge_jni_clear_exception(env)) next = NULL;
            if (current_class != owner_class) (*env)->DeleteLocalRef(env, current_class);
            current_class = next;
        }
        if (current_class != NULL && current_class != owner_class) {
            (*env)->DeleteLocalRef(env, current_class);
        }
    }

    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, owner_class);
    return found;
}

static jobject droidbridge_reflect_find_current_keybind_screen(
        JNIEnv *env, jobject minecraft, bool require_capture) {
    int budget = 420;
    return droidbridge_reflect_find_matching_keybind_screen(
            env, minecraft, require_capture, 1, &budget);
}

static jobject droidbridge_get_static_self_instance_only(JNIEnv *env, jclass candidate_class) {
    if (env == NULL || candidate_class == NULL) return NULL;
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    jclass field_class = (*env)->FindClass(env, "java/lang/reflect/Field");
    if (class_class == NULL || field_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return NULL;
    }
    jmethodID get_declared_fields = (*env)->GetMethodID(
            env, class_class, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID get_superclass = (*env)->GetMethodID(
            env, class_class, "getSuperclass", "()Ljava/lang/Class;");
    jmethodID field_get_type = (*env)->GetMethodID(
            env, field_class, "getType", "()Ljava/lang/Class;");
    jmethodID field_get_modifiers = (*env)->GetMethodID(
            env, field_class, "getModifiers", "()I");
    jmethodID field_set_accessible = (*env)->GetMethodID(
            env, field_class, "setAccessible", "(Z)V");
    jmethodID field_get = (*env)->GetMethodID(
            env, field_class, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (get_declared_fields == NULL || get_superclass == NULL || field_get_type == NULL
            || field_get_modifiers == NULL || field_set_accessible == NULL
            || field_get == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, field_class);
        (*env)->DeleteLocalRef(env, class_class);
        return NULL;
    }

    jobject instance = NULL;
    jobject current_class = candidate_class;
    int depth = 0;
    while (current_class != NULL && depth++ < 5 && instance == NULL) {
        jobjectArray fields = (jobjectArray) (*env)->CallObjectMethod(
                env, current_class, get_declared_fields);
        if (droidbridge_jni_clear_exception(env)) fields = NULL;
        if (fields != NULL) {
            jsize count = (*env)->GetArrayLength(env, fields);
            for (jsize i = 0; i < count && instance == NULL; i++) {
                jobject field = (*env)->GetObjectArrayElement(env, fields, i);
                if (field == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jint modifiers = (*env)->CallIntMethod(env, field, field_get_modifiers);
                jobject type = (*env)->CallObjectMethod(env, field, field_get_type);
                bool same_type = type != NULL
                        && (*env)->IsSameObject(env, type, candidate_class);
                droidbridge_jni_clear_exception(env);
                if ((modifiers & 0x0008) != 0 && same_type) {
                    (*env)->CallVoidMethod(env, field, field_set_accessible, JNI_TRUE);
                    droidbridge_jni_clear_exception(env);
                    jobject value = (*env)->CallObjectMethod(env, field, field_get, NULL);
                    if (!droidbridge_jni_clear_exception(env) && value != NULL) {
                        instance = value;
                    } else {
                        droidbridge_jni_clear_exception(env);
                    }
                }
                if (type != NULL) (*env)->DeleteLocalRef(env, type);
                (*env)->DeleteLocalRef(env, field);
            }
            (*env)->DeleteLocalRef(env, fields);
        }
        if (instance != NULL) break;
        jobject next = (*env)->CallObjectMethod(env, current_class, get_superclass);
        if (droidbridge_jni_clear_exception(env)) next = NULL;
        if (current_class != candidate_class) (*env)->DeleteLocalRef(env, current_class);
        current_class = next;
    }
    if (current_class != NULL && current_class != candidate_class) {
        (*env)->DeleteLocalRef(env, current_class);
    }
    (*env)->DeleteLocalRef(env, field_class);
    (*env)->DeleteLocalRef(env, class_class);
    return instance;
}

static bool droidbridge_query_owner_keybind_state(
        JNIEnv *env, jobject owner, bool require_capture, char *screen_name, size_t screen_name_size) {
    if (screen_name != NULL && screen_name_size > 0) screen_name[0] = '\0';
    if (env == NULL || owner == NULL) return false;
    jobject screen = droidbridge_reflect_find_current_keybind_screen(
            env, owner, require_capture);
    if (screen == NULL) return false;
    droidbridge_runtime_object_class_name(env, screen, screen_name, screen_name_size);
    (*env)->DeleteLocalRef(env, screen);
    return true;
}

/*
 * Fully obfuscated vanilla jars do not expose Minecraft/class_310. Discover the
 * live client class from render/client thread stack frames, then resolve only a
 * static field whose declared type is that same class. No arbitrary game method
 * is invoked, so this fallback is safe across loaders and old versions.
 */
static bool droidbridge_query_keybind_state_from_runtime_threads(
        JNIEnv *env, bool require_capture, char *screen_name, size_t screen_name_size) {
    if (env == NULL) return false;
    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
    jclass map_class = (*env)->FindClass(env, "java/util/Map");
    jclass set_class = (*env)->FindClass(env, "java/util/Set");
    jclass stack_element_class = (*env)->FindClass(env, "java/lang/StackTraceElement");
    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (thread_class == NULL || map_class == NULL || set_class == NULL
            || stack_element_class == NULL || class_class == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        return false;
    }
    jmethodID get_all_stacks = (*env)->GetStaticMethodID(
            env, thread_class, "getAllStackTraces", "()Ljava/util/Map;");
    jmethodID get_context_loader = (*env)->GetMethodID(
            env, thread_class, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID get_thread_name = (*env)->GetMethodID(
            env, thread_class, "getName", "()Ljava/lang/String;");
    jmethodID get_stack_trace = (*env)->GetMethodID(
            env, thread_class, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    jmethodID key_set = (*env)->GetMethodID(env, map_class, "keySet", "()Ljava/util/Set;");
    jmethodID to_array = (*env)->GetMethodID(env, set_class, "toArray", "()[Ljava/lang/Object;");
    jmethodID stack_get_class_name = (*env)->GetMethodID(
            env, stack_element_class, "getClassName", "()Ljava/lang/String;");
    jmethodID for_name = (*env)->GetStaticMethodID(
            env, class_class, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (get_all_stacks == NULL || get_context_loader == NULL || get_thread_name == NULL
            || get_stack_trace == NULL || key_set == NULL || to_array == NULL
            || stack_get_class_name == NULL || for_name == NULL
            || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, stack_element_class);
        (*env)->DeleteLocalRef(env, set_class);
        (*env)->DeleteLocalRef(env, map_class);
        (*env)->DeleteLocalRef(env, thread_class);
        return false;
    }

    jobject stack_map = (*env)->CallStaticObjectMethod(env, thread_class, get_all_stacks);
    jobject thread_set = stack_map == NULL ? NULL
            : (*env)->CallObjectMethod(env, stack_map, key_set);
    jobjectArray threads = thread_set == NULL ? NULL
            : (jobjectArray) (*env)->CallObjectMethod(env, thread_set, to_array);
    droidbridge_jni_clear_exception(env);
    bool found = false;
    int loaded_candidates = 0;

    /* Prefer render/client/main threads, then scan the remaining runtime threads. */
    for (int pass = 0; pass < 2 && !found; pass++) {
        jsize thread_count = threads == NULL ? 0 : (*env)->GetArrayLength(env, threads);
        for (jsize i = 0; i < thread_count && !found && loaded_candidates < 160; i++) {
            jobject thread = (*env)->GetObjectArrayElement(env, threads, i);
            if (thread == NULL || droidbridge_jni_clear_exception(env)) {
                droidbridge_jni_clear_exception(env);
                continue;
            }
            jstring thread_name_value = (jstring) (*env)->CallObjectMethod(
                    env, thread, get_thread_name);
            char thread_name[192];
            droidbridge_copy_jstring(env, thread_name_value, thread_name, sizeof(thread_name));
            if (thread_name_value != NULL) (*env)->DeleteLocalRef(env, thread_name_value);
            bool likely_game_thread = droidbridge_ascii_contains_ci(thread_name, "render")
                    || droidbridge_ascii_contains_ci(thread_name, "client")
                    || droidbridge_ascii_contains_ci(thread_name, "minecraft")
                    || strcasecmp(thread_name, "main") == 0;
            if ((pass == 0) != likely_game_thread) {
                (*env)->DeleteLocalRef(env, thread);
                continue;
            }
            jobject loader = (*env)->CallObjectMethod(env, thread, get_context_loader);
            jobjectArray stack = (jobjectArray) (*env)->CallObjectMethod(
                    env, thread, get_stack_trace);
            if (droidbridge_jni_clear_exception(env)) {
                droidbridge_jni_clear_exception(env);
                if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
                if (stack != NULL) (*env)->DeleteLocalRef(env, stack);
                (*env)->DeleteLocalRef(env, thread);
                continue;
            }
            jsize stack_count = stack == NULL ? 0 : (*env)->GetArrayLength(env, stack);
            if (stack_count > 48) stack_count = 48;
            for (jsize frame_index = 0; frame_index < stack_count
                    && !found && loaded_candidates < 160; frame_index++) {
                jobject frame = (*env)->GetObjectArrayElement(env, stack, frame_index);
                if (frame == NULL || droidbridge_jni_clear_exception(env)) {
                    droidbridge_jni_clear_exception(env);
                    continue;
                }
                jstring class_name_value = (jstring) (*env)->CallObjectMethod(
                        env, frame, stack_get_class_name);
                char class_name[512];
                droidbridge_copy_jstring(env, class_name_value, class_name, sizeof(class_name));
                bool skip_platform = droidbridge_ascii_contains_ci(class_name, "java.")
                        || droidbridge_ascii_contains_ci(class_name, "jdk.")
                        || droidbridge_ascii_contains_ci(class_name, "sun.")
                        || droidbridge_ascii_contains_ci(class_name, "org.lwjgl.")
                        || droidbridge_ascii_contains_ci(class_name, "com.mojang.blaze3d.")
                        || droidbridge_ascii_contains_ci(class_name, "net.minecraftforge.")
                        || droidbridge_ascii_contains_ci(class_name, "net.fabricmc.");
                if (!skip_platform && class_name_value != NULL && loader != NULL) {
                    jobject candidate_class = (*env)->CallStaticObjectMethod(
                            env, class_class, for_name, class_name_value, JNI_FALSE, loader);
                    if (!droidbridge_jni_clear_exception(env) && candidate_class != NULL) {
                        loaded_candidates++;
                        jobject instance = droidbridge_get_static_self_instance_only(
                                env, (jclass) candidate_class);
                        if (instance != NULL) {
                            found = droidbridge_query_owner_keybind_state(
                                    env, instance, require_capture, screen_name, screen_name_size);
                            if (found) {
                                droidbridge_cache_minecraft_instance(env, instance);
                            }
                            (*env)->DeleteLocalRef(env, instance);
                        }
                        (*env)->DeleteLocalRef(env, candidate_class);
                    } else {
                        droidbridge_jni_clear_exception(env);
                    }
                }
                if (class_name_value != NULL) (*env)->DeleteLocalRef(env, class_name_value);
                (*env)->DeleteLocalRef(env, frame);
            }
            if (stack != NULL) (*env)->DeleteLocalRef(env, stack);
            if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
            (*env)->DeleteLocalRef(env, thread);
        }
    }

    if (threads != NULL) (*env)->DeleteLocalRef(env, threads);
    if (thread_set != NULL) (*env)->DeleteLocalRef(env, thread_set);
    if (stack_map != NULL) (*env)->DeleteLocalRef(env, stack_map);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, stack_element_class);
    (*env)->DeleteLocalRef(env, set_class);
    (*env)->DeleteLocalRef(env, map_class);
    (*env)->DeleteLocalRef(env, thread_class);
    return found;
}

static jboolean droidbridge_query_minecraft_controls(JNIEnv *android_env, bool require_capture) {
    (void) android_env;
    JNIEnv *env = droidbridge_get_runtime_env();
    if (env == NULL) return JNI_FALSE;

    bool result = false;
    bool minecraft_instance_resolved = false;
    char screen_name[384];
    screen_name[0] = '\0';

    jobject minecraft = droidbridge_get_cached_minecraft_instance(env);
    if (minecraft != NULL) {
        minecraft_instance_resolved = true;
        result = droidbridge_query_owner_keybind_state(
                env, minecraft, require_capture, screen_name, sizeof(screen_name));
        (*env)->DeleteLocalRef(env, minecraft);
    } else {
        jclass minecraft_class = droidbridge_find_minecraft_class(env);
        if (minecraft_class != NULL) {
            minecraft = droidbridge_get_minecraft_instance(env, minecraft_class);
            if (minecraft != NULL) {
                minecraft_instance_resolved = true;
                droidbridge_cache_minecraft_instance(env, minecraft);
                result = droidbridge_query_owner_keybind_state(
                        env, minecraft, require_capture, screen_name, sizeof(screen_name));
                (*env)->DeleteLocalRef(env, minecraft);
            }
            (*env)->DeleteLocalRef(env, minecraft_class);
        }
    }

    /*
     * A false result from the real Minecraft owner is authoritative: it means the
     * current screen is not waiting for a binding. The old code nevertheless ran
     * the full runtime-thread scan after every ordinary menu click, causing the
     * controller click stutter. Use that fallback only when no client instance
     * could be resolved at all (fully obfuscated vanilla bootstrap case).
     */
    if (!result && !minecraft_instance_resolved) {
        result = droidbridge_query_keybind_state_from_runtime_threads(
                env, require_capture, screen_name, sizeof(screen_name));
    }

    if (result) {
        __android_log_print(ANDROID_LOG_INFO, "DroidBridgeKeybind",
                            "runtime structural query screen=%s mode=%s result=1",
                            screen_name[0] == '\0' ? "<obfuscated>" : screen_name,
                            require_capture ? "capture" : "controls");
    }
    return result ? JNI_TRUE : JNI_FALSE;
}


static jboolean droidbridge_native_is_minecraft_keybind_capture_active(
        JNIEnv *env, __attribute__((unused)) jclass clazz) {
    return droidbridge_query_minecraft_controls(env, true);
}

static jboolean droidbridge_native_is_minecraft_controls_screen_open(
        JNIEnv *env, __attribute__((unused)) jclass clazz) {
    return droidbridge_query_minecraft_controls(env, false);
}

jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    if (droidbridge_environ->dalvikJavaVMPtr == NULL) {
        __android_log_print(ANDROID_LOG_INFO, "Native", "Saving DVM environ...");
        //Save dalvik global JavaVM pointer
        droidbridge_environ->dalvikJavaVMPtr = vm;
        (*vm)->GetEnv(vm, (void**) &droidbridge_environ->dalvikJNIEnvPtr_ANDROID, JNI_VERSION_1_4);
        droidbridge_environ->bridgeClazz = (*droidbridge_environ->dalvikJNIEnvPtr_ANDROID)->NewGlobalRef(droidbridge_environ->dalvikJNIEnvPtr_ANDROID,(*droidbridge_environ->dalvikJNIEnvPtr_ANDROID) ->FindClass(droidbridge_environ->dalvikJNIEnvPtr_ANDROID,"org/lwjgl/glfw/CallbackBridge"));
        droidbridge_environ->method_accessAndroidClipboard = (*droidbridge_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(droidbridge_environ->dalvikJNIEnvPtr_ANDROID, droidbridge_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
        droidbridge_environ->method_onGrabStateChanged = (*droidbridge_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(droidbridge_environ->dalvikJNIEnvPtr_ANDROID, droidbridge_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        droidbridge_environ->method_onCursorShapeChanged = (*droidbridge_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(droidbridge_environ->dalvikJNIEnvPtr_ANDROID, droidbridge_environ->bridgeClazz, "onCursorShapeChanged", "(I)V");
        droidbridge_environ->method_onNativeCursorPosSilentlyChanged = (*droidbridge_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(droidbridge_environ->dalvikJNIEnvPtr_ANDROID, droidbridge_environ->bridgeClazz, "onNativeCursorPosSilentlyChanged", "(FF)V");
        droidbridge_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (droidbridge_environ->dalvikJavaVMPtr != vm) {
        __android_log_print(ANDROID_LOG_INFO, "Native", "Saving JVM environ...");
        droidbridge_environ->runtimeJavaVMPtr = vm;
        (*vm)->GetEnv(vm, (void**) &droidbridge_environ->runtimeJNIEnvPtr_JRE, JNI_VERSION_1_4);

        /*
         * Keep the working launch order used before the Freedreno rewrite.
         *
         * The early-hook experiment installed ProcessImpl forkAndExec before the
         * LWJGL GLFW class/buffers were resolved. On Java 25 + LWJGL 3.4.1 this
         * makes 26.1.x Fabric crash immediately after the exec hook registers
         * (SIGSEGV in libjvm.so). Resolve GLFW first, then install the hooks.
         */
        droidbridge_environ->vmGlfwClass = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->NewGlobalRef(droidbridge_environ->runtimeJNIEnvPtr_JRE, (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->FindClass(droidbridge_environ->runtimeJNIEnvPtr_JRE, "org/lwjgl/glfw/GLFW"));
        droidbridge_environ->method_glftSetWindowAttrib = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetStaticMethodID(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, "glfwSetWindowAttrib", "(JII)V");
        droidbridge_environ->method_internalWindowSizeChanged = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetStaticMethodID(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, "internalWindowSizeChanged", "(JII)V");
        jfieldID field_keyDownBuffer = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetStaticFieldID(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject keyDownBufferJ = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetStaticObjectField(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, field_keyDownBuffer);
        droidbridge_environ->keyDownBuffer = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetDirectBufferAddress(droidbridge_environ->runtimeJNIEnvPtr_JRE, keyDownBufferJ);
        jfieldID field_mouseDownBuffer = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetStaticFieldID(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject mouseDownBufferJ = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetStaticObjectField(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, field_mouseDownBuffer);
        droidbridge_environ->mouseDownBuffer = (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->GetDirectBufferAddress(droidbridge_environ->runtimeJNIEnvPtr_JRE, mouseDownBufferJ);

        hookExec();
        installLwjglDlopenHook();
        installEMUIIteratorMititgation();

        // Register the BTA-only gamepad native readers in the OpenJDK/LWJGL JVM.
        // Android button/axis events are written from the Dalvik side through CallbackBridge,
        // while BTA polls gamepad state from the OpenJDK side through DroidBridgeBtaGamepad.
        registerBtaGamepadFunctions(droidbridge_environ->runtimeJNIEnvPtr_JRE);

        // Disable SDL3 HIDAPI joystick backend to prevent double detection.
        // When both HIDAPI and the Android Input API backend are active, they can both detect
        // the same controller and trigger two SDL_JOYSTICKDEVICEADDED events. The second event
        // causes crashes in SDL_UpdateJoysticks because SDL3 ends up with two conflicting
        // joystick entries for the same physical device.
        // Set this hint before any SDL_Init(SDL_INIT_JOYSTICK) call (Controlify via JNA, or
        // our initializeControllerSubsystems). SDL hints are process-wide and persist.
        void *sdl3_handle_hint = dlopen("libSDL3.so", RTLD_NOW);
        droidbridge_sdl3_after_library_load("libSDL3.so", sdl3_handle_hint);
        if (sdl3_handle_hint != NULL) {
            typedef int (*SDL_SetHint_t)(const char *name, const char *value);
            SDL_SetHint_t sdl_set_hint = (SDL_SetHint_t)dlsym(sdl3_handle_hint, "SDL_SetHint");
            if (sdl_set_hint != NULL) {
                sdl_set_hint("SDL_JOYSTICK_HIDAPI", "0");
                __android_log_print(ANDROID_LOG_INFO, "SDL_Hint", "Disabled HIDAPI joystick backend to prevent double detection");
            } else {
                __android_log_print(ANDROID_LOG_WARN, "SDL_Hint", "SDL_SetHint symbol not found");
            }
        } else {
            __android_log_print(ANDROID_LOG_WARN, "SDL_Hint", "libSDL3.so not loaded yet, HIDAPI hint not set");
        }
    }

    if(droidbridge_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4);
        registerFunctions(env);
    }
    droidbridge_environ->isGrabbing = JNI_FALSE;

    return JNI_VERSION_1_4;
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv * env, jclass cls, jlong window, jlong callbackptr) { \
    void** oldCallback = (void**) &droidbridge_environ->GLFW_invoke_##NAME; \
    droidbridge_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) *oldCallback; \
}

ADD_CALLBACK_WWIN(Char)
ADD_CALLBACK_WWIN(CharMods)
ADD_CALLBACK_WWIN(CursorEnter)
ADD_CALLBACK_WWIN(CursorPos)
ADD_CALLBACK_WWIN(FramebufferSize)
ADD_CALLBACK_WWIN(Key)
ADD_CALLBACK_WWIN(MouseButton)
ADD_CALLBACK_WWIN(Scroll)
ADD_CALLBACK_WWIN(WindowSize)

#undef ADD_CALLBACK_WWIN

#define GLFW_ARROW_CURSOR 0x00036001
#define GLFW_IBEAM_CURSOR 0x00036002
#define DROIDBRIDGE_CURSOR_HANDLE_MAGIC ((jlong)0x4442435500000000ULL)

static jint cursor_shape_from_handle(jlong cursor) {
    if (cursor == 0) return GLFW_ARROW_CURSOR;
    if ((cursor & (jlong)0xffffffff00000000ULL) == DROIDBRIDGE_CURSOR_HANDLE_MAGIC) {
        return (jint)(cursor & 0xffffffffLL);
    }
    return GLFW_ARROW_CURSOR;
}

static JNIEnv* get_attached_dalvik_env(void) {
    if (droidbridge_environ == NULL || droidbridge_environ->dalvikJavaVMPtr == NULL) return NULL;

    JNIEnv *dalvikEnv = NULL;
    jint env_result = (*droidbridge_environ->dalvikJavaVMPtr)->GetEnv(
            droidbridge_environ->dalvikJavaVMPtr,
            (void**) &dalvikEnv,
            JNI_VERSION_1_4
    );
    if (env_result == JNI_EDETACHED) {
        env_result = (*droidbridge_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                droidbridge_environ->dalvikJavaVMPtr,
                (void**) &dalvikEnv,
                NULL
        );
    }
    if (env_result != JNI_OK) return NULL;
    return dalvikEnv;
}

static void notify_android_cursor_shape(jint shape) {
    if (droidbridge_environ == NULL
            || droidbridge_environ->bridgeClazz == NULL
            || droidbridge_environ->method_onCursorShapeChanged == NULL) {
        return;
    }

    JNIEnv *dalvikEnv = get_attached_dalvik_env();
    if (dalvikEnv == NULL) return;

    (*dalvikEnv)->CallStaticVoidMethod(
            dalvikEnv,
            droidbridge_environ->bridgeClazz,
            droidbridge_environ->method_onCursorShapeChanged,
            shape
    );
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
    }
}

static void notify_android_cursor_pos_silently(jfloat x, jfloat y) {
    if (droidbridge_environ == NULL
            || droidbridge_environ->bridgeClazz == NULL
            || droidbridge_environ->method_onNativeCursorPosSilentlyChanged == NULL) {
        return;
    }

    JNIEnv *dalvikEnv = get_attached_dalvik_env();
    if (dalvikEnv == NULL) return;

    (*dalvikEnv)->CallStaticVoidMethod(
            dalvikEnv,
            droidbridge_environ->bridgeClazz,
            droidbridge_environ->method_onNativeCursorPosSilentlyChanged,
            x,
            y
    );
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
    }
}

JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwCreateStandardCursor(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint shape) {
    return (jlong)(DROIDBRIDGE_CURSOR_HANDLE_MAGIC | ((jlong) shape & 0xffffffffLL));
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwDestroyCursor(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        __attribute__((unused)) jlong cursor) {
    // Android has no native desktop cursor object to destroy.
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetCursor(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        __attribute__((unused)) jlong window,
        jlong cursor) {
    notify_android_cursor_shape(cursor_shape_from_handle(cursor));
}

void handleFramebufferSizeJava(long window, int w, int h) {
    (*droidbridge_environ->runtimeJNIEnvPtr_JRE)->CallStaticVoidMethod(droidbridge_environ->runtimeJNIEnvPtr_JRE, droidbridge_environ->vmGlfwClass, droidbridge_environ->method_internalWindowSizeChanged, (long)window, w, h);
}

void droidbridgePumpEvents(void* window) {
    if(droidbridge_environ->shouldUpdateMouse) {
        /*
         * Preserve sub-pixel mouse motion all the way into GLFW. Hardware mouse
         * sensitivity can intentionally scale a one-pixel Android delta below
         * 1.0 (for example 25% -> 0.25). Flooring here made Minecraft wait for
         * several small samples to accumulate before it observed any movement,
         * which felt like input latency at low DPI/sensitivity settings.
         */
        droidbridge_environ->GLFW_invoke_CursorPos(window,
                                             droidbridge_environ->cursorX,
                                             droidbridge_environ->cursorY);
    }

    size_t index = droidbridge_environ->outEventIndex;
    size_t targetIndex = droidbridge_environ->outTargetIndex;

    while (targetIndex != index) {
        GLFWInputEvent event = droidbridge_environ->events[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(droidbridge_environ->GLFW_invoke_Char) droidbridge_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(droidbridge_environ->GLFW_invoke_CharMods) droidbridge_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_KEY:
                if(droidbridge_environ->GLFW_invoke_Key) droidbridge_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(droidbridge_environ->GLFW_invoke_MouseButton) droidbridge_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            case EVENT_TYPE_CURSOR_ENTER:
                if(droidbridge_environ->GLFW_invoke_CursorEnter) droidbridge_environ->GLFW_invoke_CursorEnter(window, event.i1);
                break;
            case EVENT_TYPE_SCROLL:
                if(droidbridge_environ->GLFW_invoke_Scroll) droidbridge_environ->GLFW_invoke_Scroll(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_FRAMEBUFFER_SIZE:
                handleFramebufferSizeJava(droidbridge_environ->showingWindow, event.i1, event.i2);
                if(droidbridge_environ->GLFW_invoke_FramebufferSize) droidbridge_environ->GLFW_invoke_FramebufferSize(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_WINDOW_SIZE:
                handleFramebufferSizeJava(droidbridge_environ->showingWindow, event.i1, event.i2);
                if(droidbridge_environ->GLFW_invoke_WindowSize) droidbridge_environ->GLFW_invoke_WindowSize(window, event.i1, event.i2);
                break;
        }

        index++;
        if (index >= EVENT_WINDOW_SIZE)
            index -= EVENT_WINDOW_SIZE;
    }

    // The out target index is updated by the rewinder
}

/** Prepare the library for sending out callbacks to all windows */
void droidbridgeStartPumping() {
    size_t counter = atomic_load_explicit(&droidbridge_environ->eventCounter, memory_order_acquire);
    size_t index = droidbridge_environ->outEventIndex;

    unsigned targetIndex = index + counter;
    if (targetIndex >= EVENT_WINDOW_SIZE)
        targetIndex -= EVENT_WINDOW_SIZE;

    // Only accessed by one unique thread, no need for atomic store
    droidbridge_environ->inEventCount = counter;
    droidbridge_environ->outTargetIndex = targetIndex;

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((droidbridge_environ->cLastX != droidbridge_environ->cursorX || droidbridge_environ->cLastY != droidbridge_environ->cursorY) && droidbridge_environ->GLFW_invoke_CursorPos) {
        droidbridge_environ->cLastX = droidbridge_environ->cursorX;
        droidbridge_environ->cLastY = droidbridge_environ->cursorY;
        droidbridge_environ->shouldUpdateMouse = true;
    }
}

/** Prepare the library for the next round of new events */
void droidbridgeStopPumping() {
    droidbridge_environ->outEventIndex = droidbridge_environ->outTargetIndex;

    // New events may have arrived while pumping, so remove only the difference before the start and end of execution
    atomic_fetch_sub_explicit(&droidbridge_environ->eventCounter, droidbridge_environ->inEventCount, memory_order_acquire);
    // Make sure the next frame won't send mouse updates if it's unnecessary
    droidbridge_environ->shouldUpdateMouse = false;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jobject xpos,
        jobject ypos) {
*(double*)(*env)->GetDirectBufferAddress(env, xpos) = droidbridge_environ->cursorX;
*(double*)(*env)->GetDirectBufferAddress(env, ypos) = droidbridge_environ->cursorY;
}

JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(__attribute__((unused)) jlong window, jint lengthx, jdouble* xpos, jint lengthy, jdouble* ypos) {
*xpos = droidbridge_environ->cursorX;
*ypos = droidbridge_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window,
jdoubleArray xpos, jdoubleArray ypos) {
(*env)->SetDoubleArrayRegion(env, xpos, 0,1, &droidbridge_environ->cursorX);
(*env)->SetDoubleArrayRegion(env, ypos, 0,1, &droidbridge_environ->cursorY);
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) jlong window, jdouble xpos,
        jdouble ypos) {
if (droidbridge_should_dispatch_cursor_warp()) {
    // Leave cLastX/cLastY unchanged. droidbridgeStartPumping() will observe
    // the position change and deliver one normal GLFW CursorPos callback,
    // matching desktop behavior used by Controllable's virtual mouse.
    droidbridge_environ->cursorX = xpos;
    droidbridge_environ->cursorY = ypos;
} else {
    // Vanilla/grabbed cursor recentres stay silent to avoid camera jumps.
    droidbridge_environ->cLastX = droidbridge_environ->cursorX = xpos;
    droidbridge_environ->cLastY = droidbridge_environ->cursorY = ypos;
}
notify_android_cursor_pos_silently((jfloat)xpos, (jfloat)ypos);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jdouble xpos,
        jdouble ypos) {
JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(window, xpos, ypos);
}



void sendData(int type, int i1, int i2, int i3, int i4) {
    GLFWInputEvent *event = &droidbridge_environ->events[droidbridge_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    if (++droidbridge_environ->inEventIndex >= EVENT_WINDOW_SIZE)
        droidbridge_environ->inEventIndex -= EVENT_WINDOW_SIZE;

    atomic_fetch_add_explicit(&droidbridge_environ->eventCounter, 1, memory_order_acquire);
}

/**
 * This function is meant as a substitute for SharedLibraryUtil.getLibraryPath() that just returns 0
 * (thus making the parent Java function return null). This is done to avoid using the LWJGL's default function,
 * which will hang the crappy EMUI linker by dlopen()ing inside of dl_iterate_phdr().
 * @return 0, to make the parent Java function return null immediately.
 * For reference: https://github.com/DroidBridge/lwjgl3/blob/fix_huawei_hang/modules/lwjgl/core/src/main/java/org/lwjgl/system/SharedLibraryUtil.java
 */
jint getLibraryPath_fix(__attribute__((unused)) JNIEnv *env,
                        __attribute__((unused)) jclass class,
                        __attribute__((unused)) jlong pLibAddress,
                        __attribute__((unused)) jlong sOutAddress,
                        __attribute__((unused)) jint bufSize){
    return 0;
}

/**
 * Install the linker hang mitigation that is meant to prevent linker hangs on old EMUI firmware.
 */
void installEMUIIteratorMititgation() {
    if(getenv("DROIDBRIDGE_EMUI_ITERATOR_MITIGATE") == NULL) return;
    __android_log_print(ANDROID_LOG_INFO, "EMUIIteratorFix", "Installing...");
    JNIEnv* env = droidbridge_environ->runtimeJNIEnvPtr_JRE;
    jclass sharedLibraryUtil = (*env)->FindClass(env, "org/lwjgl/system/SharedLibraryUtil");
    if(sharedLibraryUtil == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "EMUIIteratorFix", "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod getLibraryPathMethod[] = {
            {"getLibraryPath", "(JJI)I", &getLibraryPath_fix}
    };
    if((*env)->RegisterNatives(env, sharedLibraryUtil, getLibraryPathMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EMUIIteratorFix", "Failed to register the mitigation method");
        (*env)->ExceptionClear(env);
    }
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    droidbridge_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}
// Cursor shapes are desktop-only. Android has no native cursor to change.
void critical_set_cursor_shape(jint shape) {
    // Critical-native path cannot safely call back into Java. The non-critical
    // GLFW cursor hooks below are the normal path used by Minecraft menus.
    (void) shape;
}
void noncritical_set_cursor_shape(__attribute__((unused)) JNIEnv* env,
                                  __attribute__((unused)) jclass clazz,
                                  jint shape) {
    notify_android_cursor_shape(shape);
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, __attribute__((unused)) jclass clazz, jint action, jbyteArray copySrc) {
#ifdef DEBUG
    LOGD("Debug: Clipboard access is going on\n", droidbridge_environ->isUseStackQueueCall);
#endif

    JNIEnv *dalvikEnv;
    // Attach to the Android (ART) JVM to call clipboard APIs.
    // We intentionally do NOT call DetachCurrentThread afterwards: SDL3 also attaches the
    // Render thread to ART and caches the JNIEnv in its mThreadKey TLS slot. Calling
    // DetachCurrentThread here invalidates that cached env, causing the next
    // SDL_UpdateJoysticks() call to use a stale pointer and crash with SIGSEGV at 0xb8
    // (DeleteLocalRef on a null JNINativeInterface_). The ART attachment for this thread
    // is safe to leave live — ART will clean it up when the thread exits.
    (*droidbridge_environ->dalvikJavaVMPtr)->AttachCurrentThread(droidbridge_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    assert(dalvikEnv != NULL);
    assert(droidbridge_environ->bridgeClazz != NULL);

    LOGD("Clipboard: Converting string\n");
    char *copySrcC = NULL;
    jstring copyDst = NULL;
    if (copySrc) {
        jsize copyLen = (*env)->GetArrayLength(env, copySrc);
        jbyte *copyBytes = (*env)->GetByteArrayElements(env, copySrc, NULL);
        if (copyBytes != NULL) {
            copySrcC = (char *)calloc((size_t)copyLen + 1, 1);
            if (copySrcC != NULL) {
                memcpy(copySrcC, copyBytes, (size_t)copyLen);
                copySrcC[copyLen] = '\0';
                copyDst = (*dalvikEnv)->NewStringUTF(dalvikEnv, copySrcC);
            }
            (*env)->ReleaseByteArrayElements(env, copySrc, copyBytes, JNI_ABORT);
        }
    }

    LOGD("Clipboard: Calling 2nd\n");
    // Extract the result and delete its ART local ref explicitly, since we are no longer
    // relying on DetachCurrentThread to clean up ART's local ref table for this thread.
    jstring artResult = (jstring) (*dalvikEnv)->CallStaticObjectMethod(dalvikEnv, droidbridge_environ->bridgeClazz, droidbridge_environ->method_accessAndroidClipboard, action, copyDst);
    jstring pasteDst = convertStringJVM(dalvikEnv, env, artResult);
    if (artResult != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, artResult);
    }

    if (copyDst != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);
    }
    free(copySrcC);
    return pasteDst;
}
JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetCursorShape(JNIEnv* env, jclass clazz, jint shape) {
(void)env; (void)clazz;
notify_android_cursor_shape(shape);
}

JNIEXPORT jboolean JNICALL JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean inputReady) {
#ifdef DEBUG
    LOGD("Debug: Changing input state, isReady=%d, droidbridge_environ->isUseStackQueueCall=%d\n", inputReady, droidbridge_environ->isUseStackQueueCall);
#endif
    __android_log_print(ANDROID_LOG_INFO, "NativeInput", "Input ready: %i", inputReady);
    droidbridge_environ->isInputReady = inputReady;
    return droidbridge_environ->isUseStackQueueCall;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean inputReady) {
    return JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(inputReady);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
JNIEnv *dalvikEnv;
// Same as nativeClipboard: do NOT call DetachCurrentThread — SDL3 shares this thread's
// ART attachment via mThreadKey and a detach here would corrupt SDL3's cached JNIEnv.
(*droidbridge_environ->dalvikJavaVMPtr)->AttachCurrentThread(droidbridge_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
(*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, droidbridge_environ->bridgeClazz, droidbridge_environ->method_onGrabStateChanged, grabbing);
droidbridge_environ->isGrabbing = grabbing;
}

jboolean critical_send_char(jchar codepoint) {
    if (droidbridge_environ->GLFW_invoke_Char && droidbridge_environ->isInputReady) {
        if (droidbridge_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR, codepoint, 0, 0, 0);
        } else {
            droidbridge_environ->GLFW_invoke_Char((void*) droidbridge_environ->showingWindow, (unsigned int) codepoint);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint) {
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    if (droidbridge_environ->GLFW_invoke_CharMods && droidbridge_environ->isInputReady) {
        if (droidbridge_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR_MODS, (int) codepoint, mods, 0, 0);
        } else {
            droidbridge_environ->GLFW_invoke_CharMods((void*) droidbridge_environ->showingWindow, codepoint, mods);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char_mods(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}
// Need to add this back after for Snapshot builds
/*
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorEnter(JNIEnv* env, jclass clazz, jint entered) {
    if (droidbridge_environ->GLFW_invoke_CursorEnter && droidbridge_environ->isInputReady) {
        droidbridge_environ->GLFW_invoke_CursorEnter(droidbridge_environ->showingWindow, entered);
    }
}
*/

static void droidbridge_send_cursor_pos_internal(jfloat x, jfloat y, bool hardware_mouse) {
#ifdef DEBUG
    LOGD("Sending cursor position source=%s\n", hardware_mouse ? "hardware-mouse" : "logical");
#endif
    if (droidbridge_environ->GLFW_invoke_CursorPos && droidbridge_environ->isInputReady) {
#ifdef DEBUG
        LOGD("droidbridge_environ->GLFW_invoke_CursorPos && droidbridge_environ->isInputReady\n");
#endif
        if (!droidbridge_environ->isCursorEntered) {
            if (droidbridge_environ->GLFW_invoke_CursorEnter) {
                droidbridge_environ->isCursorEntered = true;
                if (droidbridge_environ->isUseStackQueueCall) {
                    sendData(EVENT_TYPE_CURSOR_ENTER, 1, 0, 0, 0);
                } else {
                    droidbridge_environ->GLFW_invoke_CursorEnter((void*) droidbridge_environ->showingWindow, 1);
                }
            } else if (droidbridge_environ->isGrabbing) {
                // Some Minecraft versions does not use GLFWCursorEnterCallback
                // This is a smart check, as Minecraft will not in grab mode if already not.
                droidbridge_environ->isCursorEntered = true;
            }
        }

        if (!droidbridge_environ->isUseStackQueueCall) {
            droidbridge_environ->GLFW_invoke_CursorPos((void*) droidbridge_environ->showingWindow, (double) x, (double) y);
        } else {
            /*
             * Remember whether this hardware-mouse update exactly matches the
             * position most recently pumped to GLFW before overwriting cursorX/Y.
             * Normal hardware motion already differs from cLastX/cLastY and needs
             * no special invalidation. Only a true same-position recenter needs a
             * one-shot forced callback so a physical mouse can re-synchronise
             * after Minecraft recentres it.
             */
            bool force_hardware_recenter = hardware_mouse
                    && droidbridge_environ->cLastX == (double) x
                    && droidbridge_environ->cLastY == (double) y;

            droidbridge_environ->cursorX = x;
            droidbridge_environ->cursorY = y;

            /*
             * Keep controller mods, touch controls, and Minecraft cursor warps on
             * the ordinary logical path. A real Android hardware mouse gets the
             * recenter repair only when the requested position would otherwise be
             * indistinguishable from the already-pumped cursor position.
             */
            if (force_hardware_recenter) {
                droidbridge_environ->cLastX = NAN;
                droidbridge_environ->cLastY = NAN;
            }
        }
    }
}

void critical_send_cursor_pos(jfloat x, jfloat y) {
    droidbridge_send_cursor_pos_internal(x, y, false);
}

void noncritical_send_cursor_pos(__attribute__((unused)) JNIEnv* env,
                                 __attribute__((unused)) jclass clazz,
                                 jfloat x,
                                 jfloat y) {
    droidbridge_send_cursor_pos_internal(x, y, false);
}

void critical_send_hardware_cursor_pos(jfloat x, jfloat y) {
    droidbridge_send_cursor_pos_internal(x, y, true);
}

void noncritical_send_hardware_cursor_pos(__attribute__((unused)) JNIEnv* env,
                                          __attribute__((unused)) jclass clazz,
                                          jfloat x,
                                          jfloat y) {
    droidbridge_send_cursor_pos_internal(x, y, true);
}

static jclass droidbridge_find_game_runtime_class(
        JNIEnv *env,
        const char *slash_name,
        const char *dot_name) {
    if (env == NULL || slash_name == NULL || dot_name == NULL) return NULL;

    jclass direct = (*env)->FindClass(env, slash_name);
    if (direct != NULL && !droidbridge_jni_clear_exception(env)) {
        return direct;
    }
    droidbridge_jni_clear_exception(env);

    jclass minecraft_class = droidbridge_find_minecraft_class(env);
    if (minecraft_class == NULL) return NULL;

    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (class_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, minecraft_class);
        return NULL;
    }

    jmethodID get_class_loader = (*env)->GetMethodID(
            env, class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID for_name = (*env)->GetStaticMethodID(
            env, class_class, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (get_class_loader == NULL || for_name == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, minecraft_class);
        return NULL;
    }

    jobject loader = (*env)->CallObjectMethod(env, minecraft_class, get_class_loader);
    jstring class_name = (*env)->NewStringUTF(env, dot_name);
    jclass result = NULL;
    if (loader != NULL && class_name != NULL && !droidbridge_jni_clear_exception(env)) {
        result = (jclass) (*env)->CallStaticObjectMethod(
                env, class_class, for_name, class_name, JNI_FALSE, loader);
        if (droidbridge_jni_clear_exception(env)) result = NULL;
    } else {
        droidbridge_jni_clear_exception(env);
    }

    if (class_name != NULL) (*env)->DeleteLocalRef(env, class_name);
    if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, minecraft_class);
    return result;
}

/*
 * Resolve the LWJGL2 compatibility input class from the same runtime
 * ClassLoader that loaded Minecraft. Native controller events can originate on
 * an ART-created thread, where a plain FindClass() may only see bootstrap
 * classes even after the thread has been attached to the embedded OpenJDK VM.
 */
static jclass droidbridge_find_lwjgl2_input_class(JNIEnv *env) {
    if (env == NULL) return NULL;

    jclass direct = (*env)->FindClass(env, "org/lwjgl/input/GLFWInputImplementation");
    if (direct != NULL && !droidbridge_jni_clear_exception(env)) {
        return direct;
    }
    droidbridge_jni_clear_exception(env);

    jclass minecraft_class = droidbridge_find_minecraft_class(env);
    if (minecraft_class == NULL) return NULL;

    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
    if (class_class == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, minecraft_class);
        return NULL;
    }

    jmethodID get_class_loader = (*env)->GetMethodID(
            env, class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID for_name = (*env)->GetStaticMethodID(
            env, class_class, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (get_class_loader == NULL || for_name == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, class_class);
        (*env)->DeleteLocalRef(env, minecraft_class);
        return NULL;
    }

    jobject loader = (*env)->CallObjectMethod(env, minecraft_class, get_class_loader);
    jstring class_name = (*env)->NewStringUTF(
            env, "org.lwjgl.input.GLFWInputImplementation");
    jclass result = NULL;
    if (loader != NULL && class_name != NULL && !droidbridge_jni_clear_exception(env)) {
        result = (jclass) (*env)->CallStaticObjectMethod(
                env, class_class, for_name, class_name, JNI_FALSE, loader);
        if (droidbridge_jni_clear_exception(env)) result = NULL;
    } else {
        droidbridge_jni_clear_exception(env);
    }

    if (class_name != NULL) (*env)->DeleteLocalRef(env, class_name);
    if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
    (*env)->DeleteLocalRef(env, class_class);
    (*env)->DeleteLocalRef(env, minecraft_class);
    return result;
}

/*
 * LWJGL2 Mouse.poll() keeps one more cursor baseline above
 * GLFWInputImplementation. In an ungrabbed menu it computes dx/dy from the
 * static absolute_x/absolute_y fields before accepting the current poll
 * position. If those static fields are still at their constructor zeroes,
 * the first controller-mode poll creates a screen-sized fake delta and BTA's
 * virtual cursor clamps to a corner.
 *
 * Seed the static Mouse coordinates to the same logical position and clear all
 * accumulated deltas so the first controller poll starts at zero movement.
 */
static void droidbridge_reset_lwjgl2_mouse_static_state(
        JNIEnv *env,
        jint logical_x,
        jint logical_y) {
    if (env == NULL) return;

    jclass mouse_class = droidbridge_find_game_runtime_class(
            env, "org/lwjgl/input/Mouse", "org.lwjgl.input.Mouse");
    if (mouse_class == NULL) return;

    jfieldID created_field = (*env)->GetStaticFieldID(env, mouse_class, "created", "Z");
    if (created_field == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, mouse_class);
        return;
    }

    jboolean created = (*env)->GetStaticBooleanField(env, mouse_class, created_field);
    if (droidbridge_jni_clear_exception(env) || created != JNI_TRUE) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, mouse_class);
        return;
    }

    const char *x_fields[] = {
            "x", "absolute_x", "event_x", "last_event_raw_x"
    };
    const char *y_fields[] = {
            "y", "absolute_y", "event_y", "last_event_raw_y"
    };
    const char *zero_fields[] = {
            "dx", "dy", "event_dx", "event_dy"
    };

    for (size_t i = 0; i < sizeof(x_fields) / sizeof(x_fields[0]); i++) {
        jfieldID field = (*env)->GetStaticFieldID(env, mouse_class, x_fields[i], "I");
        if (field == NULL || droidbridge_jni_clear_exception(env)) {
            droidbridge_jni_clear_exception(env);
            (*env)->DeleteLocalRef(env, mouse_class);
            return;
        }
        (*env)->SetStaticIntField(env, mouse_class, field, logical_x);
        if (droidbridge_jni_clear_exception(env)) {
            (*env)->DeleteLocalRef(env, mouse_class);
            return;
        }
    }

    for (size_t i = 0; i < sizeof(y_fields) / sizeof(y_fields[0]); i++) {
        jfieldID field = (*env)->GetStaticFieldID(env, mouse_class, y_fields[i], "I");
        if (field == NULL || droidbridge_jni_clear_exception(env)) {
            droidbridge_jni_clear_exception(env);
            (*env)->DeleteLocalRef(env, mouse_class);
            return;
        }
        (*env)->SetStaticIntField(env, mouse_class, field, logical_y);
        if (droidbridge_jni_clear_exception(env)) {
            (*env)->DeleteLocalRef(env, mouse_class);
            return;
        }
    }

    for (size_t i = 0; i < sizeof(zero_fields) / sizeof(zero_fields[0]); i++) {
        jfieldID field = (*env)->GetStaticFieldID(env, mouse_class, zero_fields[i], "I");
        if (field == NULL || droidbridge_jni_clear_exception(env)) {
            droidbridge_jni_clear_exception(env);
            (*env)->DeleteLocalRef(env, mouse_class);
            return;
        }
        (*env)->SetStaticIntField(env, mouse_class, field, 0);
        if (droidbridge_jni_clear_exception(env)) {
            (*env)->DeleteLocalRef(env, mouse_class);
            return;
        }
    }

    (*env)->DeleteLocalRef(env, mouse_class);
}

/*
 * LWJGL2 compatibility keeps a second mouse state inside the embedded OpenJDK
 * VM (GLFWInputImplementation.mouseX/mouseY + physical baselines). Resetting
 * only DroidBridge's Java/native GLFW cursor caches is therefore not enough.
 *
 * There are two cases we must keep in sync:
 *
 *  1) grabbed/gameplay: reset the physical and poll baselines so the first
 *     camera sample after a GUI cannot become a huge relative delta;
 *  2) ungrabbed/menu: seed LWJGL2's logical mouseX/mouseY too. BTA 8 centers
 *     its visible controller cursor independently, while this compatibility
 *     object can still be at its constructor default (0,0). The first left-
 *     stick sample then starts BTA's virtual cursor from the top-left corner.
 *
 * Modern LWJGL versions do not contain this compatibility class and simply
 * return early, so this remains isolated to LWJGL2-style consumers such as BTA.
 */
static void droidbridge_reset_lwjgl2_mouse_baseline(jfloat x, jfloat y) {
    JNIEnv *env = droidbridge_get_runtime_env();
    if (env == NULL) return;

    jclass impl_class = droidbridge_find_lwjgl2_input_class(env);
    if (impl_class == NULL) return;

    jfieldID singleton_field = (*env)->GetStaticFieldID(
            env,
            impl_class,
            "singleton",
            "Lorg/lwjgl/input/GLFWInputImplementation;");
    if (singleton_field == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, impl_class);
        return;
    }

    jobject singleton = (*env)->GetStaticObjectField(env, impl_class, singleton_field);
    if (singleton == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, impl_class);
        return;
    }

    jfieldID grab_field = (*env)->GetFieldID(env, impl_class, "grab", "Z");
    if (grab_field == NULL || droidbridge_jni_clear_exception(env)) {
        droidbridge_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, singleton);
        (*env)->DeleteLocalRef(env, impl_class);
        return;
    }

    jboolean grabbed = (*env)->GetBooleanField(env, singleton, grab_field);
    if (droidbridge_jni_clear_exception(env)) {
        (*env)->DeleteLocalRef(env, singleton);
        (*env)->DeleteLocalRef(env, impl_class);
        return;
    }

    jfieldID last_physical_x = (*env)->GetFieldID(env, impl_class, "lastPhysicalX", "I");
    jfieldID last_physical_y = (*env)->GetFieldID(env, impl_class, "lastPhysicalY", "I");
    jfieldID mouse_x = (*env)->GetFieldID(env, impl_class, "mouseX", "I");
    jfieldID mouse_y = (*env)->GetFieldID(env, impl_class, "mouseY", "I");
    jfieldID mouse_last_x = (*env)->GetFieldID(env, impl_class, "mouseLastX", "I");
    jfieldID mouse_last_y = (*env)->GetFieldID(env, impl_class, "mouseLastY", "I");

    if (last_physical_x != NULL && last_physical_y != NULL
            && mouse_x != NULL && mouse_y != NULL
            && mouse_last_x != NULL && mouse_last_y != NULL
            && !droidbridge_jni_clear_exception(env)) {
        jint physical_x = (jint) lroundf(x);
        jint physical_y = (jint) lroundf(y);

        if (grabbed == JNI_TRUE) {
            jint logical_x = (*env)->GetIntField(env, singleton, mouse_x);
            jint logical_y = (*env)->GetIntField(env, singleton, mouse_y);
            if (!droidbridge_jni_clear_exception(env)) {
                (*env)->SetIntField(env, singleton, last_physical_x, physical_x);
                (*env)->SetIntField(env, singleton, last_physical_y, physical_y);
                (*env)->SetIntField(env, singleton, mouse_last_x, logical_x);
                (*env)->SetIntField(env, singleton, mouse_last_y, logical_y);
                droidbridge_jni_clear_exception(env);
            }
        } else {
            /*
             * GLFW cursor Y is top-origin, while LWJGL2 Mouse Y is bottom-origin.
             * Mirror GLFWInputImplementation.putMouseEventWithCoords() exactly:
             *     mouseY = (physicalY - Display.getHeight()) * -1
             * savedHeight is kept current by critical_send_screen_size().
             */
            jint height = droidbridge_environ != NULL ? droidbridge_environ->savedHeight : 0;
            jint logical_x = physical_x;
            jint logical_y = height > 0 ? (height - physical_y) : physical_y;

            (*env)->SetIntField(env, singleton, last_physical_x, physical_x);
            (*env)->SetIntField(env, singleton, last_physical_y, physical_y);
            (*env)->SetIntField(env, singleton, mouse_x, logical_x);
            (*env)->SetIntField(env, singleton, mouse_y, logical_y);
            (*env)->SetIntField(env, singleton, mouse_last_x, logical_x);
            (*env)->SetIntField(env, singleton, mouse_last_y, logical_y);
            if (!droidbridge_jni_clear_exception(env)) {
                droidbridge_reset_lwjgl2_mouse_static_state(env, logical_x, logical_y);
            } else {
                droidbridge_jni_clear_exception(env);
            }
        }
    } else {
        droidbridge_jni_clear_exception(env);
    }

    (*env)->DeleteLocalRef(env, singleton);
    (*env)->DeleteLocalRef(env, impl_class);
}

void critical_set_cursor_pos_silently(jfloat x, jfloat y) {
    if (droidbridge_environ == NULL) return;

    // Reset both the live cursor and the last-pumped cursor cache without
    // invoking GLFW_invoke_CursorPos and without setting shouldUpdateMouse.
    // This is used on GUI -> grabbed transitions so the last menu cursor
    // position cannot be interpreted as a giant first camera-look delta.
    droidbridge_environ->cursorX = x;
    droidbridge_environ->cursorY = y;
    droidbridge_environ->cLastX = x;
    droidbridge_environ->cLastY = y;
    droidbridge_environ->shouldUpdateMouse = false;

    // Also reset the LWJGL2 compatibility baseline used by Minecraft 1.12.2.
    droidbridge_reset_lwjgl2_mouse_baseline(x, y);
}

void noncritical_set_cursor_pos_silently(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jfloat x, jfloat y) {
    critical_set_cursor_pos_silently(x, y);
}
#define max(a,b) \
   ({ __typeof__ (a) _a = (a); \
       __typeof__ (b) _b = (b); \
     _a > _b ? _a : _b; })
void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (droidbridge_environ->GLFW_invoke_Key && droidbridge_environ->isInputReady) {
        droidbridge_environ->keyDownBuffer[max(0, key-31)] = (jbyte) action;
        if (droidbridge_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_KEY, key, scancode, action, mods);
        } else {
            droidbridge_environ->GLFW_invoke_Key((void*) droidbridge_environ->showingWindow, key, scancode, action, mods);
        }
    }
}
void noncritical_send_key(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}

void critical_send_mouse_button(jint button, jint action, jint mods) {
    if (droidbridge_environ->GLFW_invoke_MouseButton && droidbridge_environ->isInputReady) {
        droidbridge_environ->mouseDownBuffer[max(0, button)] = (jbyte) action;
        if (droidbridge_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
        } else {
            droidbridge_environ->GLFW_invoke_MouseButton((void*) droidbridge_environ->showingWindow, button, action, mods);
        }
    }
}

void noncritical_send_mouse_button(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

void critical_send_screen_size(jint width, jint height) {
    droidbridge_environ->savedWidth = width;
    droidbridge_environ->savedHeight = height;
    if (droidbridge_environ->isInputReady) {
        if (droidbridge_environ->GLFW_invoke_FramebufferSize) {
            if (droidbridge_environ->isUseStackQueueCall) {
                sendData(EVENT_TYPE_FRAMEBUFFER_SIZE, width, height, 0, 0);
            } else {
                droidbridge_environ->GLFW_invoke_FramebufferSize((void*) droidbridge_environ->showingWindow, width, height);
            }
        }

        if (droidbridge_environ->GLFW_invoke_WindowSize) {
            if (droidbridge_environ->isUseStackQueueCall) {
                sendData(EVENT_TYPE_WINDOW_SIZE, width, height, 0, 0);
            } else {
                droidbridge_environ->GLFW_invoke_WindowSize((void*) droidbridge_environ->showingWindow, width, height);
            }
        }
    }
}

void noncritical_send_screen_size(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint width, jint height) {
    critical_send_screen_size(width, height);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (droidbridge_environ->GLFW_invoke_Scroll && droidbridge_environ->isInputReady) {
        if (droidbridge_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_SCROLL, (int)xoffset, (int)yoffset, 0, 0);
        } else {
            droidbridge_environ->GLFW_invoke_Scroll((void*) droidbridge_environ->showingWindow, (double) xoffset, (double) yoffset);
        }
    }
}

void noncritical_send_scroll(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}


JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jlong window) {
droidbridge_environ->showingWindow = (long) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint attrib, jint value) {
// Check for stack queue no longer necessary here as the JVM crash's origin is resolved
if (!droidbridge_environ->showingWindow) {
// If the window is not shown, there is nothing to do yet.
return;
}

// We cannot use droidbridge_environ->runtimeJNIEnvPtr_JRE here because that environment is attached
// on the thread that loaded droidbridge_runtime (which is the thread that first references the GLFW class)
// But this method is only called from the Android UI thread
// Technically the better solution would be to have a permanently attached env pointer stored
// in environ for the Android UI thread but this is the only place that uses it
// (very rarely, only in lifecycle callbacks) so i dont care
JavaVM* jvm = droidbridge_environ->runtimeJavaVMPtr;
JNIEnv *jvm_env = NULL;
jint env_result = (*jvm)->GetEnv(jvm, (void**)&jvm_env, JNI_VERSION_1_4);
if(env_result == JNI_EDETACHED) {
// This is the Android UI/lifecycle thread.  It is intentionally kept attached
// for later window-attrib callbacks, so attach it as a daemon JVM thread.
env_result = (*jvm)->AttachCurrentThreadAsDaemon(jvm, &jvm_env, NULL);
}
if(env_result != JNI_OK) {
printf("input_bridge nativeSetWindowAttrib() JNI call failed: %i\n", env_result);
return;
}
(*jvm_env)->CallStaticVoidMethod(
        jvm_env, droidbridge_environ->vmGlfwClass,
droidbridge_environ->method_glftSetWindowAttrib,
(jlong) droidbridge_environ->showingWindow, attrib, value
);

// Attaching every time is annoying, so stick the attachment to the Android GUI thread around
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadPresent(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jboolean present) {
    bta_gamepad_present = present ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "set present=%d", bta_gamepad_present);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadIdentity(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint deviceId,
        jstring name,
        jstring descriptor) {
    bta_set_identity(env, deviceId, name, descriptor);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadMotion(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint deviceId,
        jstring name,
        jstring descriptor,
        jfloat leftX,
        jfloat leftY,
        jfloat rightX,
        jfloat rightY,
        jfloat leftTrigger,
        jfloat rightTrigger,
        jboolean hatUp,
        jboolean hatRight,
        jboolean hatDown,
        jboolean hatLeft) {
    bta_set_identity(env, deviceId, name, descriptor);

    bta_gamepad_axes[0] = bta_clampf(leftX);
    bta_gamepad_axes[1] = bta_clampf(leftY);
    bta_gamepad_axes[2] = bta_clampf(rightX);
    bta_gamepad_axes[3] = bta_clampf(rightY);
    bta_gamepad_axes[4] = bta_trigger_to_glfw(leftTrigger);
    bta_gamepad_axes[5] = bta_trigger_to_glfw(rightTrigger);

    unsigned char hat = 0;
    if (hatUp) hat |= 1;
    if (hatRight) hat |= 2;
    if (hatDown) hat |= 4;
    if (hatLeft) hat |= 8;
    bta_gamepad_hat = hat;

    bta_gamepad_buttons[11] = hatUp ? 1 : 0;
    bta_gamepad_buttons[12] = hatRight ? 1 : 0;
    bta_gamepad_buttons[13] = hatDown ? 1 : 0;
    bta_gamepad_buttons[14] = hatLeft ? 1 : 0;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadButton(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint deviceId,
        jstring name,
        jstring descriptor,
        jint androidKeyCode,
        jboolean down) {
    bta_set_identity(env, deviceId, name, descriptor);
    if (androidKeyCode == 104) { // KEYCODE_BUTTON_L2
        bta_gamepad_axes[4] = down ? 1.0f : -1.0f;
    } else if (androidKeyCode == 105) { // KEYCODE_BUTTON_R2
        bta_gamepad_axes[5] = down ? 1.0f : -1.0f;
    }

    int button = bta_android_key_to_gamepad_button(androidKeyCode);
    if (button >= 0 && button < BTA_GAMEPAD_BUTTON_COUNT) {
        bta_gamepad_buttons[button] = down ? 1 : 0;
        __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "button key=%d mapped=%d down=%d", androidKeyCode, button, down ? 1 : 0);
    } else {
        __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "button key=%d unmapped down=%d", androidKeyCode, down ? 1 : 0);
    }
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeIsPresent(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid) {
    return (jid == 0 && bta_gamepad_present) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeName(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid) {
    if (jid != 0 || !bta_gamepad_present) return NULL;
    return (*env)->NewStringUTF(env, bta_gamepad_name);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeGuid(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid) {
    if (jid != 0 || !bta_gamepad_present) return NULL;
    return (*env)->NewStringUTF(env, bta_gamepad_guid);
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadAxes(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid,
        jobject outBuffer) {
    if (jid != 0 || !bta_gamepad_present || outBuffer == NULL) return JNI_FALSE;
    float *out = (float*) (*env)->GetDirectBufferAddress(env, outBuffer);
    if (out == NULL) return JNI_FALSE;
    for (int i = 0; i < BTA_GAMEPAD_AXIS_COUNT; i++) out[i] = bta_gamepad_axes[i];
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadButtons(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid,
        jobject outBuffer) {
    if (jid != 0 || !bta_gamepad_present || outBuffer == NULL) return JNI_FALSE;
    unsigned char *out = (unsigned char*) (*env)->GetDirectBufferAddress(env, outBuffer);
    if (out == NULL) return JNI_FALSE;
    for (int i = 0; i < BTA_GAMEPAD_BUTTON_COUNT; i++) out[i] = bta_gamepad_buttons[i];
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadHats(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid,
        jobject outBuffer) {
    if (jid != 0 || !bta_gamepad_present || outBuffer == NULL) return JNI_FALSE;
    unsigned char *out = (unsigned char*) (*env)->GetDirectBufferAddress(env, outBuffer);
    if (out == NULL) return JNI_FALSE;
    out[0] = bta_gamepad_hat;
    return JNI_TRUE;
}

static void registerBtaGamepadFunctions(JNIEnv *env) {
    if (env == NULL) return;

    jclass gamepad_class = (*env)->FindClass(env, "org/lwjgl/glfw/DroidBridgeBtaGamepad");
    if (gamepad_class == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_WARN, "BTAControllerBridge", "DroidBridgeBtaGamepad class not found in runtime JVM yet");
        return;
    }

    static const JNINativeMethod bta_gamepad_fcns[] = {
            {"nativeIsPresent", "(I)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeIsPresent},
            {"nativeName", "(I)Ljava/lang/String;", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeName},
            {"nativeGuid", "(I)Ljava/lang/String;", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeGuid},
            {"nativeReadAxes", "(ILjava/nio/FloatBuffer;)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadAxes},
            {"nativeReadButtons", "(ILjava/nio/ByteBuffer;)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadButtons},
            {"nativeReadHats", "(ILjava/nio/ByteBuffer;)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadHats},
    };

    jint result = (*env)->RegisterNatives(
            env,
            gamepad_class,
            bta_gamepad_fcns,
            sizeof(bta_gamepad_fcns) / sizeof(bta_gamepad_fcns[0])
    );

    if (result == 0) {
        __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "registered DroidBridgeBtaGamepad runtime native readers");
    } else {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_ERROR, "BTAControllerBridge", "failed registering DroidBridgeBtaGamepad natives result=%d", result);
    }
}

const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSetCursorShape", "(I)V", critical_set_cursor_shape},
        {"nativeSetCursorShape", "(I)V", noncritical_set_cursor_shape},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSendHardwareCursorPos", "(FF)V", critical_send_hardware_cursor_pos},
        {"nativeSetCursorPosSilently", "(FF)V", critical_set_cursor_pos_silently},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeIsMinecraftKeybindCaptureActive", "()Z", droidbridge_native_is_minecraft_keybind_capture_active},
        {"nativeIsMinecraftControlsScreenOpen", "()Z", droidbridge_native_is_minecraft_controls_screen_open},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
        {"nativeSendScreenSize", "(II)V", critical_send_screen_size},
        {"nativeBtaSetGamepadPresent", "(Z)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadPresent},
        {"nativeBtaSetGamepadIdentity", "(ILjava/lang/String;Ljava/lang/String;)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadIdentity},
        {"nativeBtaSetGamepadMotion", "(ILjava/lang/String;Ljava/lang/String;FFFFFFZZZZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadMotion},
        {"nativeBtaSetGamepadButton", "(ILjava/lang/String;Ljava/lang/String;IZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadButton},
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSetCursorShape", "(I)V", noncritical_set_cursor_shape},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSendHardwareCursorPos", "(FF)V", noncritical_send_hardware_cursor_pos},
        {"nativeSetCursorPosSilently", "(FF)V", noncritical_set_cursor_pos_silently},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeIsMinecraftKeybindCaptureActive", "()Z", droidbridge_native_is_minecraft_keybind_capture_active},
        {"nativeIsMinecraftControlsScreenOpen", "()Z", droidbridge_native_is_minecraft_controls_screen_open},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
        {"nativeSendScreenSize", "(II)V", noncritical_send_screen_size},
        {"nativeBtaSetGamepadPresent", "(Z)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadPresent},
        {"nativeBtaSetGamepadIdentity", "(ILjava/lang/String;Ljava/lang/String;)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadIdentity},
        {"nativeBtaSetGamepadMotion", "(ILjava/lang/String;Ljava/lang/String;FFFFFFZZZZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadMotion},
        {"nativeBtaSetGamepadButton", "(ILjava/lang/String;Ljava/lang/String;IZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadButton},
};


static bool criticalNativeAvailable;

void dvm_testCriticalNative(void* arg0, void* arg1, void* arg2, void* arg3) {
    if(arg0 != 0 && arg2 == 0 && arg3 == 0) {
        criticalNativeAvailable = false;
    }else if (arg0 == 0 && arg1 == 0){
        criticalNativeAvailable = true;
    }else {
        criticalNativeAvailable = false; // just to be safe
    }
}

static bool tryCriticalNative(JNIEnv *env) {
    static const JNINativeMethod testJNIMethod[] = {
            { "testCriticalNative", "(II)V", dvm_testCriticalNative}
    };
    jclass criticalNativeTest = (*env)->FindClass(
            env, "ca/dnamobile/javalauncher/runtime/CriticalNativeTest");
    if (criticalNativeTest == NULL) {
        (*env)->ExceptionClear(env);
        criticalNativeTest = (*env)->FindClass(
                env, "ca/dnamobile/droidbridgelauncher/runtime/CriticalNativeTest");
    }
    if (criticalNativeTest == NULL) {
        LOGD("No CriticalNativeTest class found !");
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID criticalNativeTestMethod = (*env)->GetStaticMethodID(env, criticalNativeTest, "invokeTest", "()V");
    (*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1);
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    (*env)->UnregisterNatives(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool use_critical_cc = tryCriticalNative(env);
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");

    if (use_critical_cc) {
        __android_log_print(ANDROID_LOG_INFO, "droidbridge_runtime", "CriticalNative is available. Enjoy the 4.6x times faster input!");
    } else {
        __android_log_print(ANDROID_LOG_INFO, "droidbridge_runtime", "CriticalNative is not available. Upgrade, maybe?");
    }

    const JNINativeMethod *methods = use_critical_cc ? critical_fcns : noncritical_fcns;
    const jint method_count = use_critical_cc
                              ? sizeof(critical_fcns) / sizeof(critical_fcns[0])
                              : sizeof(noncritical_fcns) / sizeof(noncritical_fcns[0]);

    (*env)->RegisterNatives(env, bridge_class, methods, method_count);
}

#define SDL_INIT_JOYSTICK   0x00000200u
#define SDL_INIT_GAMEPAD    0x00002000u
#define SDL_INIT_EVENTS     0x00004000u

static inline void initSubsystem(void) {
    typedef int (*SDL_Init_Func)(uint32_t flags);
    typedef int (*SDL_SetHint_Func)(const char *name, const char *value);
    void* handle = dlopen("libSDL3.so", RTLD_NOW);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "SDL_Init", "Failed to dlopen libSDL3.so: %s", dlerror());
        return;
    }
    // Disable HIDAPI before SDL_Init to prevent double detection (Android Input API + HIDAPI
    // would both detect the same controller, causing two SDL_JOYSTICKDEVICEADDED events and
    // a crash in SDL_UpdateJoysticks).
    SDL_SetHint_Func SDL_SetHint = (SDL_SetHint_Func)dlsym(handle, "SDL_SetHint");
    if (SDL_SetHint != NULL) {
        SDL_SetHint("SDL_JOYSTICK_HIDAPI", "0");
        __android_log_print(ANDROID_LOG_INFO, "SDL_Init", "Disabled HIDAPI joystick backend before SDL_Init");
    }
    SDL_Init_Func SDL_Init = (SDL_Init_Func)dlsym(handle, "SDL_Init");
    if (SDL_Init == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "SDL_Init", "Failed to find SDL_Init symbol");
        return;
    }
    int result = SDL_Init(SDL_INIT_GAMEPAD | SDL_INIT_JOYSTICK | SDL_INIT_EVENTS);
    __android_log_print(ANDROID_LOG_INFO, "SDL_Init", "SDL_Init result: %d", result);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_runtime_Tools_00024SDL_initializeControllerSubsystems(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    initSubsystem();
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_Tools_00024SDL_initializeControllerSubsystems(
        JNIEnv *env, jclass clazz) {
    Java_ca_dnamobile_javalauncher_runtime_Tools_00024SDL_initializeControllerSubsystems(env, clazz);
}