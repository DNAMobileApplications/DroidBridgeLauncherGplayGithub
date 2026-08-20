/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge WebRTC compatibility shim.
 *
 * Minecraft Snapshot 7+ currently ships dev.onvoid.webrtc desktop Linux
 * natives. On Android, that linux-aarch64 native asks for libudev.so.1.
 * Android does not provide libudev, so this shim satisfies the dynamic linker
 * and reports no udev devices/monitors.
 */

#include <stddef.h>

#define EXPORT __attribute__((visibility("default")))

struct udev {};
struct udev_enumerate {};
struct udev_device {};
struct udev_list_entry {};
struct udev_monitor {};

EXPORT struct udev* udev_new(void) {
    return (struct udev*) 1;
}

EXPORT struct udev* udev_ref(struct udev* udev) {
    return udev;
}

EXPORT struct udev* udev_unref(struct udev* udev) {
    return NULL;
}

EXPORT const char* udev_get_sys_path(struct udev* udev) {
    return "/sys";
}

EXPORT const char* udev_get_dev_path(struct udev* udev) {
    return "/dev";
}

EXPORT const char* udev_get_run_path(struct udev* udev) {
    return "/run/udev";
}

EXPORT int udev_get_log_priority(struct udev* udev) {
    return 0;
}

EXPORT void udev_set_log_priority(struct udev* udev, int priority) {
}

EXPORT struct udev_enumerate* udev_enumerate_new(struct udev* udev) {
    return (struct udev_enumerate*) 1;
}

EXPORT struct udev_enumerate* udev_enumerate_ref(struct udev_enumerate* enumerate) {
    return enumerate;
}

EXPORT struct udev_enumerate* udev_enumerate_unref(struct udev_enumerate* enumerate) {
    return NULL;
}

EXPORT int udev_enumerate_add_match_subsystem(struct udev_enumerate* enumerate, const char* subsystem) {
    return 0;
}

EXPORT int udev_enumerate_add_nomatch_subsystem(struct udev_enumerate* enumerate, const char* subsystem) {
    return 0;
}

EXPORT int udev_enumerate_add_match_property(struct udev_enumerate* enumerate, const char* property, const char* value) {
    return 0;
}

EXPORT int udev_enumerate_add_match_sysattr(struct udev_enumerate* enumerate, const char* sysattr, const char* value) {
    return 0;
}

EXPORT int udev_enumerate_add_nomatch_sysattr(struct udev_enumerate* enumerate, const char* sysattr, const char* value) {
    return 0;
}

EXPORT int udev_enumerate_add_match_tag(struct udev_enumerate* enumerate, const char* tag) {
    return 0;
}

EXPORT int udev_enumerate_add_match_is_initialized(struct udev_enumerate* enumerate) {
    return 0;
}

EXPORT int udev_enumerate_add_syspath(struct udev_enumerate* enumerate, const char* syspath) {
    return 0;
}

EXPORT int udev_enumerate_scan_devices(struct udev_enumerate* enumerate) {
    return 0;
}

EXPORT int udev_enumerate_scan_subsystems(struct udev_enumerate* enumerate) {
    return 0;
}

EXPORT struct udev_list_entry* udev_enumerate_get_list_entry(struct udev_enumerate* enumerate) {
    return NULL;
}

EXPORT struct udev_list_entry* udev_list_entry_get_next(struct udev_list_entry* list_entry) {
    return NULL;
}

EXPORT struct udev_list_entry* udev_list_entry_get_by_name(struct udev_list_entry* list_entry, const char* name) {
    return NULL;
}

EXPORT const char* udev_list_entry_get_name(struct udev_list_entry* list_entry) {
    return NULL;
}

EXPORT const char* udev_list_entry_get_value(struct udev_list_entry* list_entry) {
    return NULL;
}

EXPORT struct udev_device* udev_device_new_from_syspath(struct udev* udev, const char* syspath) {
    return NULL;
}

EXPORT struct udev_device* udev_device_new_from_devnum(struct udev* udev, char type, unsigned long long devnum) {
    return NULL;
}

EXPORT struct udev_device* udev_device_new_from_subsystem_sysname(struct udev* udev, const char* subsystem, const char* sysname) {
    return NULL;
}

EXPORT struct udev_device* udev_device_new_from_device_id(struct udev* udev, const char* id) {
    return NULL;
}

EXPORT struct udev_device* udev_device_new_from_environment(struct udev* udev) {
    return NULL;
}

EXPORT struct udev_device* udev_device_ref(struct udev_device* device) {
    return device;
}

EXPORT struct udev_device* udev_device_unref(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev* udev_device_get_udev(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev_device* udev_device_get_parent(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev_device* udev_device_get_parent_with_subsystem_devtype(struct udev_device* device, const char* subsystem, const char* devtype) {
    return NULL;
}

EXPORT const char* udev_device_get_devpath(struct udev_device* device) {
    return NULL;
}

EXPORT const char* udev_device_get_subsystem(struct udev_device* device) {
    return NULL;
}

EXPORT const char* udev_device_get_devtype(struct udev_device* device) {
    return NULL;
}

EXPORT const char* udev_device_get_syspath(struct udev_device* device) {
    return NULL;
}

EXPORT const char* udev_device_get_sysname(struct udev_device* device) {
    return NULL;
}

EXPORT const char* udev_device_get_sysnum(struct udev_device* device) {
    return NULL;
}

EXPORT const char* udev_device_get_devnode(struct udev_device* device) {
    return NULL;
}

EXPORT int udev_device_get_is_initialized(struct udev_device* device) {
    return 0;
}

EXPORT const char* udev_device_get_driver(struct udev_device* device) {
    return NULL;
}

EXPORT unsigned long long udev_device_get_devnum(struct udev_device* device) {
    return 0;
}

EXPORT const char* udev_device_get_action(struct udev_device* device) {
    return NULL;
}

EXPORT unsigned long long udev_device_get_seqnum(struct udev_device* device) {
    return 0;
}

EXPORT unsigned long long udev_device_get_usec_since_initialized(struct udev_device* device) {
    return 0;
}

EXPORT const char* udev_device_get_property_value(struct udev_device* device, const char* key) {
    return NULL;
}

EXPORT const char* udev_device_get_sysattr_value(struct udev_device* device, const char* sysattr) {
    return NULL;
}

EXPORT int udev_device_set_sysattr_value(struct udev_device* device, const char* sysattr, char* value) {
    return -1;
}

EXPORT int udev_device_has_tag(struct udev_device* device, const char* tag) {
    return 0;
}

EXPORT struct udev_list_entry* udev_device_get_devlinks_list_entry(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev_list_entry* udev_device_get_properties_list_entry(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev_list_entry* udev_device_get_tags_list_entry(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev_list_entry* udev_device_get_sysattr_list_entry(struct udev_device* device) {
    return NULL;
}

EXPORT struct udev_monitor* udev_monitor_new_from_netlink(struct udev* udev, const char* name) {
    return NULL;
}

EXPORT struct udev_monitor* udev_monitor_ref(struct udev_monitor* monitor) {
    return monitor;
}

EXPORT struct udev_monitor* udev_monitor_unref(struct udev_monitor* monitor) {
    return NULL;
}

EXPORT struct udev* udev_monitor_get_udev(struct udev_monitor* monitor) {
    return NULL;
}

EXPORT int udev_monitor_filter_add_match_subsystem_devtype(struct udev_monitor* monitor, const char* subsystem, const char* devtype) {
    return 0;
}

EXPORT int udev_monitor_filter_add_match_tag(struct udev_monitor* monitor, const char* tag) {
    return 0;
}

EXPORT int udev_monitor_filter_update(struct udev_monitor* monitor) {
    return 0;
}

EXPORT int udev_monitor_filter_remove(struct udev_monitor* monitor) {
    return 0;
}

EXPORT int udev_monitor_enable_receiving(struct udev_monitor* monitor) {
    return -1;
}

EXPORT int udev_monitor_set_receive_buffer_size(struct udev_monitor* monitor, int size) {
    return 0;
}

EXPORT int udev_monitor_get_fd(struct udev_monitor* monitor) {
    return -1;
}

EXPORT struct udev_device* udev_monitor_receive_device(struct udev_monitor* monitor) {
    return NULL;
}
