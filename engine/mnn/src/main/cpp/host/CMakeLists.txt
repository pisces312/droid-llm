# Windows host regression for mnn_chat_core. Standalone project — not part of
# the Android Gradle build. Configure with -DMNN_ROOT=<MNN source tree> and
# -DMNN_WIN_BUILD=<MNN Windows build dir> (the tree that contains MNN.lib).
#
#   cmake -S host -B <out> -G Ninja -DMNN_ROOT=... -DMNN_WIN_BUILD=...
#   cmake --build <out>
#   mnn_host_test --model D:\models\LFM2-350M-MNN

cmake_minimum_required(VERSION 3.22)
project(mnn_host_test CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Must match MNN.dll's MSVC runtime (/MD release CRT). A Debug CRT
# (_ITERATOR_DEBUG_LEVEL=2) changes std::string layout and breaks every
# by-value return across the DLL boundary (dump_config / createLLM args).
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()
if (MSVC)
    add_compile_options(/MD /EHsc /W3)
endif()

if (NOT DEFINED MNN_ROOT)
    if (DEFINED ENV{MNN_ROOT})
        set(MNN_ROOT "$ENV{MNN_ROOT}")
    else()
        message(FATAL_ERROR "MNN_ROOT is not set (MNN source tree).")
    endif()
endif()
if (NOT DEFINED MNN_WIN_BUILD)
    set(MNN_WIN_BUILD "${MNN_ROOT}/build_win64")
endif()

set(MNN_WIN_LIB "${MNN_WIN_BUILD}/MNN.lib")
if (NOT EXISTS "${MNN_WIN_LIB}")
    # Ninja/MSVC may place it under Release/ or the build root as MNN.lib
    set(MNN_WIN_LIB "${MNN_WIN_BUILD}/Release/MNN.lib")
endif()
if (NOT EXISTS "${MNN_WIN_LIB}")
    message(FATAL_ERROR
        "MNN.lib not found under ${MNN_WIN_BUILD}. "
        "Build MNN for Windows with -DMNN_BUILD_LLM=ON -DMNN_SEP_BUILD=OFF first "
        "(see docs/mnn-pc-regression.md §3).")
endif()

message(STATUS "MNN_ROOT=${MNN_ROOT}")
message(STATUS "MNN_WIN_LIB=${MNN_WIN_LIB}")

add_executable(mnn_host_test
    mnn_host_test.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../mnn_chat_core.cpp
)

target_include_directories(mnn_host_test PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/..
    "${MNN_ROOT}/include"
    "${MNN_ROOT}/transformers/llm/engine/include"
)

target_link_libraries(mnn_host_test PRIVATE "${MNN_WIN_LIB}")

# MNN.dll is needed at runtime next to the exe.
add_custom_command(TARGET mnn_host_test POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${MNN_WIN_BUILD}/MNN.dll"
        "$<TARGET_FILE_DIR:mnn_host_test>"
)
