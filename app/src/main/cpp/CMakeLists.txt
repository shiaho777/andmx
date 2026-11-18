cmake_minimum_required(VERSION 3.22.1)
project(andmxpty C CXX)

add_library(andmxpty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(andmxpty ${log-lib})

# 16KB page-size compatibility (required by Android 15+ on arm64).
target_link_options(andmxpty PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)

    add_subdirectory(whisper.cpp)

    add_library(andmxwhisper SHARED whisper_jni.cpp)
    target_link_libraries(andmxwhisper whisper ${log-lib})
    target_link_options(andmxwhisper PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
endif()
