cmake_minimum_required(VERSION 3.22.1)
project(clawmate_inference LANGUAGES C CXX)
include(Upstream.cmake)
prepare_mobiinfer(engine)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Keep debug APK inference usable as well. Only JNI functions cross the DSO boundary.
add_compile_options(-O2 -fvisibility=hidden)
set_property(GLOBAL PROPERTY JOB_POOLS compile_engine=4)
set(CMAKE_JOB_POOL_COMPILE compile_engine)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
add_subdirectory("${engine}" llama-build EXCLUDE_FROM_ALL)
add_library(clawmate_llama SHARED inference.cpp)
target_link_libraries(clawmate_llama PRIVATE llama log)
target_link_options(clawmate_llama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# AGP project dependencies can pass CMake's original .so straight to the host.
# Keep the symbol file alongside the native build tree, outside the packaged ABI directory.
set(symbol_dir "${CMAKE_CURRENT_BINARY_DIR}/symbols")
set(debug_file "${symbol_dir}/libclawmate_llama.so.debug")
add_custom_command(TARGET clawmate_llama POST_BUILD
    BYPRODUCTS "${debug_file}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${symbol_dir}"
    COMMAND "${CMAKE_OBJCOPY}" --only-keep-debug "$<TARGET_FILE:clawmate_llama>" "${debug_file}"
    COMMAND "${CMAKE_STRIP}" --strip-unneeded "$<TARGET_FILE:clawmate_llama>"
    COMMAND "${CMAKE_OBJCOPY}" "--add-gnu-debuglink=${debug_file}" "$<TARGET_FILE:clawmate_llama>"
    VERBATIM)
