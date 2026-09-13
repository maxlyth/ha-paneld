# Standalone entry point for building libhapaneld_mww.so outside Gradle. The app packages the stripped
# result from app/src/main/jniLibs; see tools/wakeword/build-prebuilt.sh.
cmake_minimum_required(VERSION 3.22.1)
project(hapaneld_mww_prebuilt C CXX)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../app/src/main/cpp/microwakeword" microwakeword)

# The linker's content-hash build ID also covers symbol-table entries that name the build directory, so
# two otherwise identical builds in different places disagree only in that note. Derive it from the
# recorded source digest instead, which keeps the ID meaningful and the bytes reproducible.
string(LENGTH "${MWW_BUILD_ID}" MWW_BUILD_ID_LENGTH)
if(NOT MWW_BUILD_ID MATCHES "^[0-9a-f]+$" OR NOT MWW_BUILD_ID_LENGTH EQUAL 40)
    message(FATAL_ERROR "MWW_BUILD_ID must be 40 lowercase hex digits")
endif()
target_link_options(hapaneld_mww PRIVATE "-Wl,--build-id=0x${MWW_BUILD_ID}")
