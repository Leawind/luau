cmake_minimum_required(VERSION 3.22)

project(luau_runtime LANGUAGES C CXX)

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

# Sanitizer group handed to -fsanitize, for example "undefined" or "address". The CI builds an
# instrumented library to run the JUnit suite against it; the shipped binaries are built with
# this empty. The address group is a local tool only: the test task preloads its runtime because
# the JVM initializes long before it loads this library (see build.gradle.kts), and the suite
# currently reports a heap-use-after-free inside the vendored Luau teardown with it, which would
# keep a CI job red until Luau is updated.
set(LUAU_JNI_SANITIZE "" CACHE STRING "Sanitizer group passed to -fsanitize, empty disables it")

set(LUAU_SANITIZER_FLAGS)
set(LUAU_SANITIZER_LINK_FLAGS)
if(LUAU_JNI_SANITIZE)
    if(MSVC)
        message(FATAL_ERROR
            "LUAU_JNI_SANITIZE=${LUAU_JNI_SANITIZE} needs a GCC or Clang toolchain, "
            "MSVC has no -fsanitize=${LUAU_JNI_SANITIZE}"
        )
    endif()
    list(APPEND LUAU_SANITIZER_FLAGS
        "-fsanitize=${LUAU_JNI_SANITIZE}"
        "-fno-sanitize-recover=${LUAU_JNI_SANITIZE}"
        "-fno-omit-frame-pointer"
        "-g"
    )
    set(LUAU_SANITIZER_LINK_FLAGS ${LUAU_SANITIZER_FLAGS})
    # The runtime of the address group deliberately stays a dynamic import: its allocator only
    # covers the whole process when the runtime was initialized before every other library, and
    # the JVM loads this one long after it has started. The test task therefore preloads the
    # shared runtime, which a runtime linked into the library cannot replace.
    if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" AND LUAU_JNI_SANITIZE STREQUAL "undefined")
        # GCC also ships the UBSan runtime as an archive, and linking it in keeps the library
        # from needing libubsan.so on the loader path of the process that loads it.
        list(APPEND LUAU_SANITIZER_LINK_FLAGS "-static-libubsan")
    endif()
    # The vendored Luau objects hold nearly all of the code the tests execute, so the
    # instrumentation has to cover them too: add_compile_options is inherited by the targets
    # created in the subdirectory below.
    add_compile_options(${LUAU_SANITIZER_FLAGS})
endif()

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

# The link step needs the sanitizer flag as well: it pulls the runtime that turns a detected
# defect into the "runtime error:" or "ERROR: AddressSanitizer" report, and -fno-sanitize-recover
# makes the process fail instead of returning from the check.
if(LUAU_SANITIZER_LINK_FLAGS)
    target_compile_options(luau_jni PRIVATE ${LUAU_SANITIZER_FLAGS})
    target_link_options(luau_jni PRIVATE ${LUAU_SANITIZER_LINK_FLAGS})
endif()

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
    )
    # A sanitizer report names the library and the offset of the fault, which only a symbol
    # table can turn into a location; the instrumented build keeps it.
    if(NOT LUAU_JNI_SANITIZE)
        target_link_options(luau_jni PRIVATE -Wl,-s)
    endif()
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
