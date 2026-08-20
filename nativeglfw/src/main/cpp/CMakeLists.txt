cmake_minimum_required(VERSION 3.22.1)
project(droidbridge_native_glfw_v82 LANGUAGES C CXX)

add_library(droidbridge_native_glfw_v82 SHARED
        droidbridge_native_glfw.cpp
        egl_owner.cpp)

target_compile_features(droidbridge_native_glfw_v82 PRIVATE cxx_std_17)

target_compile_options(droidbridge_native_glfw_v82 PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=default)

target_link_options(droidbridge_native_glfw_v82 PRIVATE
        -Wl,--no-gc-sections)

target_link_libraries(droidbridge_native_glfw_v82
        android
        log
        dl)
