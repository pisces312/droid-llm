cmake_minimum_required(VERSION 3.22.1)
project(llama_decode_repro)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-build"
)

add_executable(llama_decode_repro main.cpp)
target_compile_features(llama_decode_repro PRIVATE cxx_std_17)
target_link_libraries(llama_decode_repro PRIVATE llama)
if(ANDROID)
    set(_omp_so "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/18/lib/linux/aarch64/libomp.so")
    if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${_omp_so}")
        target_link_libraries(llama_decode_repro PRIVATE "${_omp_so}")
        target_link_options(llama_decode_repro PRIVATE "-Wl,-rpath,/data/local/tmp")
    endif()
endif()
