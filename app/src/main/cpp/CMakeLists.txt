cmake_minimum_required(VERSION 3.22.1)
project(vessel_native VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(vessel_native SHARED
    jni_bridge.cpp
    granular_engine.cpp
    grain.cpp
    audio_buffer.cpp
    aaudio_player.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(EGL-lib EGL)
find_library(GLESv3-lib GLESv3)

target_link_libraries(vessel_native
    ${log-lib}
    ${android-lib}
    ${aaudio-lib}
    ${EGL-lib}
    ${GLESv3-lib}
)

target_compile_options(vessel_native PRIVATE
    -O2
    -ffast-math
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_include_directories(vessel_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
