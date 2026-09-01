cmake_minimum_required(VERSION 3.22)
project(ncnn_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NCNN prebuilt
set(NCNN_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/ncnn/include)
set(NCNN_LIB ${CMAKE_SOURCE_DIR}/ncnn/lib/arm64-v8a/libncnn.a)

add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_LIB})

add_library(ncnn_jni SHARED ncnn_jni.cpp)

target_include_directories(ncnn_jni PRIVATE ${NCNN_INCLUDE_DIR})

target_link_libraries(ncnn_jni
    ncnn
    android
    log
    jnigraphics
)

# OpenMP
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    target_link_libraries(ncnn_jni OpenMP::OpenMP_CXX)
endif()

# Strip debug symbols for release
set_target_properties(ncnn_jni PROPERTIES CXX_VISIBILITY_PRESET hidden)
