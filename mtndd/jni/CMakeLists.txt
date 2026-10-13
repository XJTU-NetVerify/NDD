cmake_minimum_required(VERSION 3.17)
project(mtpndd_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED COMPONENTS JVM)

set(MTPNDD_LOG_LEVEL "1" CACHE STRING "MTPNDD log level: 0=QUIET, 1=INFO, 2=DEBUG")
set_property(CACHE MTPNDD_LOG_LEVEL PROPERTY STRINGS 0 1 2)

add_library(mtpnddjni SHARED src/main/native/mtpndd_jni.c)
target_include_directories(mtpnddjni PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(mtpnddjni PRIVATE MTPNDD_LOG_LEVEL=${MTPNDD_LOG_LEVEL})
set_target_properties(mtpnddjni PROPERTIES OUTPUT_NAME "mtpnddjni" POSITION_INDEPENDENT_CODE ON)

if(TARGET mtpndd::mtpndd)
    # In-tree build via top-level CMakeLists: link against sibling targets.
    target_link_libraries(mtpnddjni PRIVATE mtpndd::mtpndd sylvan::sylvan pthread m)
else()
    # Standalone build: explicit paths to pre-built libraries.
    set(MTPNDD_INCLUDE_DIR  "" CACHE PATH "Path to MTPNDD public headers (directory with mtpndd.h)")
    set(MTPNDD_LIBRARY_PATH "" CACHE FILEPATH "Path to libmtpndd.a")
    set(MTPNDD_SYLVAN_INCLUDE_DIR "" CACHE PATH "Path to Sylvan public headers")
    set(MTPNDD_SYLVAN_LIBRARY     "" CACHE FILEPATH "Path to libsylvan library")
    set(MTPNDD_LACE_LIBRARY       "" CACHE FILEPATH "Optional path to liblace")

    if(NOT MTPNDD_INCLUDE_DIR OR NOT EXISTS "${MTPNDD_INCLUDE_DIR}/mtpndd.h")
        message(FATAL_ERROR "Standalone JNI build requires MTPNDD_INCLUDE_DIR pointing at mtpndd headers, "
                            "or add this directory via add_subdirectory() from the top-level CMakeLists.")
    endif()

    target_include_directories(mtpnddjni PRIVATE
        ${MTPNDD_INCLUDE_DIR}
        ${MTPNDD_SYLVAN_INCLUDE_DIR}
    )
    target_link_libraries(mtpnddjni PRIVATE
        ${MTPNDD_LIBRARY_PATH}
        ${MTPNDD_SYLVAN_LIBRARY}
        ${MTPNDD_LACE_LIBRARY}
        pthread
        m
    )
endif()
