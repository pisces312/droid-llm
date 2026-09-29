cmake_minimum_required(VERSION 3.22.1)
project(llamacpp_chat_jni)

# Keep llama.cpp / ggml static and inside this .so to limit global symbols
# (DESIGN.md §6 symbol-clash mitigation).
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
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-build"
)

add_library(llamacpp_chat_jni SHARED
    llamacpp_jni.cpp
)

set_target_properties(llamacpp_chat_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

target_compile_features(llamacpp_chat_jni PRIVATE cxx_std_17)
target_compile_options(llamacpp_chat_jni PRIVATE -fvisibility=hidden)

target_link_libraries(llamacpp_chat_jni PRIVATE
    llama
    android
    log
)

# Only Java_* JNI exports should be visible; hide everything from static deps.
target_link_options(llamacpp_chat_jni PRIVATE "-Wl,--exclude-libs,ALL")

# 16 KB page size (required on Android 15+ 16 KB-page devices). Mirrors NDK r27's
# APP_SUPPORT_FLEXIBLE_PAGE_SIZES recipe (build/core/build-binary.mk:135-140): the
# link flag 16 KB-aligns LOAD segments, the define keeps our own code away from the
# 4 KB PAGE_SIZE macro.
target_compile_options(llamacpp_chat_jni PRIVATE "-D__BIONIC_NO_PAGE_SIZE_MACRO")
target_link_options(llamacpp_chat_jni PRIVATE "-Wl,-z,max-page-size=16384")
