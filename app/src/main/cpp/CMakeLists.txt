cmake_minimum_required(VERSION 3.22.1)
project(andmxpty C)

add_library(andmxpty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(andmxpty ${log-lib})

# 16KB page-size compatibility (required by Android 15+ on arm64).
target_link_options(andmxpty PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
