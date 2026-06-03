cmake_minimum_required(VERSION 3.22.1)

project(hapaneld_led C)

# Built for the fleet ABIs (arm64-v8a, armeabi-v7a) — see app/build.gradle.kts ndk.abiFilters.
# Produces libhapaneld_led.so, loaded by hardware/NativeLed.kt via System.loadLibrary("hapaneld_led").
add_library(hapaneld_led SHARED led_jni.c)

find_library(log-lib log)
target_link_libraries(hapaneld_led ${log-lib})
