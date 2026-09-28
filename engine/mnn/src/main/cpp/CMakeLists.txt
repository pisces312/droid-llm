cmake_minimum_required(VERSION 3.22.1)
project(mnn_chat_jni)

# MNN_ROOT: path to an MNN source/build tree that contains
#   include/MNN/...
#   transformers/llm/engine/include/llm/llm.hpp
#   project/android/build_64/lib/libMNN.so   (MNN_BUILD_LLM=ON)
#
# Never defaulted to a machine-specific path: the Gradle build forwards
# -DMNN_ROOT=$MNN_ROOT (or the droid.mnnRoot property). See AGENTS.md.
if (NOT DEFINED MNN_ROOT)
    if (DEFINED ENV{MNN_ROOT})
        set(MNN_ROOT "$ENV{MNN_ROOT}")
    else()
        message(FATAL_ERROR
            "MNN_ROOT is not set. Build with -DMNN_ROOT=<path to MNN tree> or export "
            "MNN_ROOT in the environment (see AGENTS.md / engine/mnn/build.gradle.kts).")
    endif()
endif()

set(MNN_LIB "${MNN_ROOT}/project/android/build_64/lib/libMNN.so")
if (NOT EXISTS "${MNN_LIB}")
    # Some trees keep the so one level up.
    set(MNN_LIB "${MNN_ROOT}/project/android/build_64/libMNN.so")
endif()
if (NOT EXISTS "${MNN_LIB}")
    message(FATAL_ERROR "libMNN.so not found under ${MNN_ROOT}/project/android/build_64. Build MNN with MNN_BUILD_LLM=ON first.")
endif()

message(STATUS "MNN_ROOT=${MNN_ROOT}")
message(STATUS "MNN_LIB=${MNN_LIB}")

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_LIB}")

add_library(mnn_chat_core STATIC
    mnn_chat_core.cpp
)
set_target_properties(mnn_chat_core PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_compile_features(mnn_chat_core PRIVATE cxx_std_17)
target_compile_options(mnn_chat_core PRIVATE -fvisibility=hidden)
target_include_directories(mnn_chat_core PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}"
    "${MNN_ROOT}/include"
    "${MNN_ROOT}/transformers/llm/engine/include"
)

add_library(mnn_chat_jni SHARED
    mnn_chat_jni.cpp
)
target_link_libraries(mnn_chat_jni PRIVATE mnn_chat_core)

set_target_properties(mnn_chat_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

target_compile_features(mnn_chat_jni PRIVATE cxx_std_17)
target_compile_options(mnn_chat_jni PRIVATE -fvisibility=hidden)

target_include_directories(mnn_chat_jni PRIVATE
    "${MNN_ROOT}/include"
    "${MNN_ROOT}/transformers/llm/engine/include"
)

target_link_libraries(mnn_chat_jni PRIVATE
    MNN
    android
    log
)

target_link_options(mnn_chat_jni PRIVATE "-Wl,--exclude-libs,ALL")
