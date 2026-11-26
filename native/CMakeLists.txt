cmake_minimum_required(VERSION 3.22)

project(suterxom_luau_runtime LANGUAGES C CXX)

# Statically link the C runtime so the library loads without the VC++ redistributable.
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LUAU_RUNTIME_VERSION "0.0.0-UNKNOWN" CACHE STRING "Luau Runtime Mod version")
set(LUAU_UPSTREAM_VERSION "UNKNOWN" CACHE STRING "Upstream Luau version")
set(LUAU_UPSTREAM_COMMIT "UNKNOWN" CACHE STRING "Upstream Luau commit")
set(LUAU_JNI_ABI_VERSION "0" CACHE STRING "JNI ABI version")

set(LUAU_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(LUAU_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LUAU_BUILD_WEB OFF CACHE BOOL "" FORCE)
set(LUAU_BUILD_SHARED OFF CACHE BOOL "" FORCE)

add_subdirectory(luau EXCLUDE_FROM_ALL)

find_package(JNI REQUIRED)

add_library(luau_jni SHARED
    src/luau_jni.cpp
)

target_include_directories(luau_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
)

target_compile_definitions(luau_jni PRIVATE
    LUAU_RUNTIME_VERSION="${LUAU_RUNTIME_VERSION}"
    LUAU_UPSTREAM_VERSION="${LUAU_UPSTREAM_VERSION}"
    LUAU_UPSTREAM_COMMIT="${LUAU_UPSTREAM_COMMIT}"
    LUAU_JNI_ABI_VERSION=${LUAU_JNI_ABI_VERSION}
)

target_link_libraries(luau_jni PRIVATE
    Luau.Analysis
    Luau.Compiler
    Luau.VM
)

if(MSVC)
    target_compile_options(luau_jni PRIVATE /W4)
else()
    target_compile_options(luau_jni PRIVATE -Wall -Wextra -Wpedantic -Wno-unused-parameter)
endif()

if(NOT MSVC AND NOT APPLE)
    target_link_options(luau_jni PRIVATE -Wl,--exclude-libs,ALL)
endif()

set_target_properties(luau_jni PROPERTIES
    OUTPUT_NAME "luau_jni"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
    LIBRARY_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/out"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/out"
    LIBRARY_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/out"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/out"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/out"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/out"
)
