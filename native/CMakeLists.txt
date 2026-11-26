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

# Many mods share one process, so the library publishes its JNI entry points and nothing
# else. JNIEXPORT asks for default visibility, every other symbol is hidden. The preset is
# set before add_subdirectory so the statically linked Luau objects are hidden too, which is
# what Mach-O needs: ld64 has no --exclude-libs.
if(NOT MSVC)
    set(CMAKE_C_VISIBILITY_PRESET hidden)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
    # Keep recorded source paths independent of where the checkout lives.
    add_compile_options("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
endif()

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

# Upstream Luau is vendored code with its own warning policy, so its headers stay outside
# the warning gate below and only src/luau_jni.cpp has to be warning free.
set(LUAU_VENDOR_INCLUDE_DIRS
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/Analysis/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/Ast/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/Bytecode/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/Common/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/Compiler/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/Config/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/luau/VM/include"
)

if(MSVC)
    foreach(luau_include_dir IN LISTS LUAU_VENDOR_INCLUDE_DIRS)
        target_compile_options(luau_jni PRIVATE "/external:I${luau_include_dir}")
    endforeach()
    target_compile_options(luau_jni PRIVATE /W4 /WX /external:W0)
    target_link_options(luau_jni PRIVATE /Brepro)
else()
    foreach(luau_include_dir IN LISTS LUAU_VENDOR_INCLUDE_DIRS)
        target_compile_options(luau_jni PRIVATE "SHELL:-isystem ${luau_include_dir}")
    endforeach()
    target_compile_options(luau_jni PRIVATE -Wall -Wextra -Wpedantic -Wno-unused-parameter -Werror)
endif()

if(APPLE)
    # Hidden visibility empties the export table down to the JNI entry points; -Wl,-x drops
    # the local symbols from the dylib as well.
    target_link_options(luau_jni PRIVATE -Wl,-x)
elseif(NOT MSVC)
    # The C++ runtime is linked statically so the library depends on the system C library
    # only: a dynamically linked libstdc++ would pin the player's distribution version.
    target_link_options(luau_jni PRIVATE
        -static-libstdc++
        -static-libgcc
        -Wl,--exclude-libs,ALL
        -Wl,-s
    )
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
