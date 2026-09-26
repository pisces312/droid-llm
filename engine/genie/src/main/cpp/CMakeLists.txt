cmake_minimum_required(VERSION 3.22.1)
project(genie_chat_jni)

# QNN_SDK_ROOT_PATH: QAIRT/QNN SDK root containing
#   include/Genie/*.h
#   lib/aarch64-android/libGenie.so
if (NOT DEFINED QNN_SDK_ROOT_PATH)
    if (DEFINED ENV{QAIRT_SDK_ROOT})
        set(QNN_SDK_ROOT_PATH "$ENV{QAIRT_SDK_ROOT}")
    elseif (DEFINED ENV{QAIRT_PATH})
        set(QNN_SDK_ROOT_PATH "$ENV{QAIRT_PATH}")
    else()
        set(QNN_SDK_ROOT_PATH "D:/dev/qairt/2.50.0.260828")
    endif()
endif()

set(GENIE_HEADERS "${QNN_SDK_ROOT_PATH}/include/Genie")
set(GENIE_LIB "${QNN_SDK_ROOT_PATH}/lib/aarch64-android/libGenie.so")

if (NOT EXISTS "${GENIE_LIB}")
    message(FATAL_ERROR "libGenie.so not found at ${GENIE_LIB}. Set QNN_SDK_ROOT_PATH to a QAIRT SDK with Genie.")
endif()
if (NOT EXISTS "${GENIE_HEADERS}/GenieDialog.h")
    message(FATAL_ERROR "Genie headers not found at ${GENIE_HEADERS}.")
endif()

message(STATUS "QNN_SDK_ROOT_PATH=${QNN_SDK_ROOT_PATH}")
message(STATUS "GENIE_LIB=${GENIE_LIB}")

add_library(Genie SHARED IMPORTED)
set_target_properties(Genie PROPERTIES IMPORTED_LOCATION "${GENIE_LIB}")

add_library(genie_chat_jni SHARED
    genie_chat_jni.cpp
)

set_target_properties(genie_chat_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

target_compile_features(genie_chat_jni PRIVATE cxx_std_17)
target_compile_options(genie_chat_jni PRIVATE -fvisibility=hidden)

target_include_directories(genie_chat_jni PRIVATE
    "${GENIE_HEADERS}"
)

target_link_libraries(genie_chat_jni PRIVATE
    Genie
    android
    log
)

# Do not re-export Genie/QNN symbols from our thin wrapper.
target_link_options(genie_chat_jni PRIVATE "-Wl,--exclude-libs,ALL")
