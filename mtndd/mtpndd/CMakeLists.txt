project(mtpndd C)

file(GLOB MTPNDD_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/*.c")

add_library(mtpndd STATIC ${MTPNDD_SOURCES})
add_library(mtpndd::mtpndd ALIAS mtpndd)
target_compile_features(mtpndd PUBLIC c_std_11)
set_target_properties(mtpndd PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(mtpndd
    PUBLIC
        ${CMAKE_CURRENT_LIST_DIR}
)
target_compile_options(mtpndd PRIVATE -Wall -Wextra -Wno-unused-parameter)

set(MTPNDD_LOG_LEVEL "1" CACHE STRING "MTPNDD log level: 0=QUIET, 1=INFO, 2=DEBUG")
set_property(CACHE MTPNDD_LOG_LEVEL PROPERTY STRINGS 0 1 2)
target_compile_definitions(mtpndd PUBLIC MTPNDD_LOG_LEVEL=${MTPNDD_LOG_LEVEL})
if(TARGET sylvan)
    target_compile_definitions(sylvan PUBLIC MTPNDD_LOG_LEVEL=${MTPNDD_LOG_LEVEL})
endif()

if(TARGET sylvan::sylvan)
    target_link_libraries(mtpndd PUBLIC sylvan::sylvan)
    # Expose sylvan's internal source dir so MTPNDD can include headers that
    # are not part of the installed public set (e.g. sylvan_refs.h).
    if(TARGET sylvan)
        get_target_property(_sylvan_src_dir sylvan SOURCE_DIR)
        if(_sylvan_src_dir)
            target_include_directories(mtpndd PUBLIC ${_sylvan_src_dir})
        endif()
    endif()
else()
    if(NOT DEFINED SYLVAN_ROOT)
        set(SYLVAN_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." CACHE PATH "Path to Sylvan root")
    endif()

    find_path(SYLVAN_INCLUDE_DIR sylvan.h
        PATHS
            "${SYLVAN_ROOT}/include"
            "${SYLVAN_ROOT}/src"
            "${SYLVAN_ROOT}"
            ENV INCLUDE
        NO_DEFAULT_PATH
    )

    if(SYLVAN_INCLUDE_DIR)
        target_include_directories(mtpndd PUBLIC ${SYLVAN_INCLUDE_DIR})
    else()
        message(WARNING "Sylvan headers not found. Set -DSYLVAN_ROOT to the Sylvan source tree.")
    endif()

    find_library(SYLVAN_LIBRARY NAMES sylvan HINTS
        "${SYLVAN_ROOT}/lib" "${SYLVAN_ROOT}/build" "${SYLVAN_ROOT}"
    )

    if(SYLVAN_LIBRARY)
        target_link_libraries(mtpndd PUBLIC ${SYLVAN_LIBRARY})
    else()
        set(LACE_CANDIDATES
            "${SYLVAN_ROOT}/src/lace/lace.c"
            "${SYLVAN_ROOT}/src/lace.c"
            "${SYLVAN_ROOT}/lace.c"
        )
        foreach(f ${LACE_CANDIDATES})
            if(EXISTS "${f}")
                target_sources(mtpndd PRIVATE "${f}")
                break()
            endif()
        endforeach()
    endif()
endif()

find_package(Threads REQUIRED)
target_link_libraries(mtpndd PUBLIC Threads::Threads m)

install(TARGETS mtpndd ARCHIVE DESTINATION lib)
install(DIRECTORY ${CMAKE_CURRENT_LIST_DIR}/ DESTINATION include FILES_MATCHING PATTERN "*.h")

add_executable(mtpndd_nqueens_test test/nqueens.c)
target_link_libraries(mtpndd_nqueens_test PRIVATE mtpndd::mtpndd)
target_compile_features(mtpndd_nqueens_test PRIVATE c_std_11)
target_compile_options(mtpndd_nqueens_test PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)
option(MTPNDD_NQUEENS_ENABLE_DOT "Dump DOT graphs when building the N-Queens test formula" OFF)
if(MTPNDD_NQUEENS_ENABLE_DOT)
    target_compile_definitions(mtpndd_nqueens_test PRIVATE MTPNDD_NQUEENS_ENABLE_DOT=1)
endif()

add_executable(mtpndd_nqueens_benchmark test/nqueens_benchmark.c)
target_link_libraries(mtpndd_nqueens_benchmark PRIVATE mtpndd::mtpndd m)
target_compile_features(mtpndd_nqueens_benchmark PRIVATE c_std_11)
target_compile_options(mtpndd_nqueens_benchmark PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

add_executable(mtpndd_nqueens_parallel_benchmark test/nqueens_parallel_benchmark.c)
target_link_libraries(mtpndd_nqueens_parallel_benchmark PRIVATE mtpndd::mtpndd m)
target_compile_features(mtpndd_nqueens_parallel_benchmark PRIVATE c_std_11)
target_compile_options(mtpndd_nqueens_parallel_benchmark PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

add_executable(mtpndd_conversion_test test/conversion.c)
target_link_libraries(mtpndd_conversion_test PRIVATE mtpndd::mtpndd)
target_compile_features(mtpndd_conversion_test PRIVATE c_std_11)
target_compile_options(mtpndd_conversion_test PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

add_executable(mtpndd_fraction_test test/fraction.c)
target_link_libraries(mtpndd_fraction_test PRIVATE mtpndd::mtpndd)
target_compile_features(mtpndd_fraction_test PRIVATE c_std_11)
target_compile_options(mtpndd_fraction_test PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

add_executable(mtpndd_arith_benchmark test/arith_benchmark.c)
target_link_libraries(mtpndd_arith_benchmark PRIVATE mtpndd::mtpndd)
target_compile_features(mtpndd_arith_benchmark PRIVATE c_std_11)
target_compile_options(mtpndd_arith_benchmark PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

# Assertions must execute even in Release; upstream tests contain API calls in assert().
foreach(test_target mtpndd_nqueens_test mtpndd_conversion_test mtpndd_fraction_test mtpndd_arith_benchmark mtpndd_nqueens_benchmark mtpndd_nqueens_parallel_benchmark)
  target_compile_options(${test_target} PRIVATE -UNDEBUG)
endforeach()
if(BUILD_TESTING)
  add_test(NAME mtndd_nqueens8 COMMAND mtpndd_nqueens_test 8)
  add_test(NAME mtndd_conversion COMMAND mtpndd_conversion_test)
  add_test(NAME mtndd_fraction COMMAND mtpndd_fraction_test)
  set_tests_properties(mtndd_nqueens8 mtndd_conversion mtndd_fraction
    PROPERTIES TIMEOUT 120 RUN_SERIAL TRUE)
endif()

if(BUILD_TESTING)
  foreach(workers 1 6)
    add_test(NAME mtndd_fraction_${workers} COMMAND mtpndd_fraction_test)
    set_tests_properties(mtndd_fraction_${workers} PROPERTIES
      ENVIRONMENT "MTNDD_TEST_WORKERS=${workers}" TIMEOUT 120 RUN_SERIAL TRUE)
  endforeach()
  add_executable(mtndd_cache_test test/cache_publication.c)
  target_link_libraries(mtndd_cache_test PRIVATE mtpndd::mtpndd)
  target_compile_options(mtndd_cache_test PRIVATE -UNDEBUG -Wall -Wextra -Werror)
  add_test(NAME mtndd_cache_publication COMMAND mtndd_cache_test)
  set_tests_properties(mtndd_cache_publication PROPERTIES TIMEOUT 60)
endif()
if(BUILD_TESTING)
  add_executable(mtndd_arithmetic_oracle test/arithmetic_oracle.c)
  target_link_libraries(mtndd_arithmetic_oracle PRIVATE mtpndd::mtpndd)
  target_compile_options(mtndd_arithmetic_oracle PRIVATE -UNDEBUG -Wall -Wextra -Werror)
  foreach(workers 1 6)
    add_test(NAME mtndd_arithmetic_oracle_${workers} COMMAND mtndd_arithmetic_oracle ${workers})
    set_tests_properties(mtndd_arithmetic_oracle_${workers} PROPERTIES TIMEOUT 120 RUN_SERIAL TRUE)
  endforeach()
endif()
