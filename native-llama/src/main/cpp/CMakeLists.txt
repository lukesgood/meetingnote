cmake_minimum_required(VERSION 3.22)
project(llama_jni C CXX)

# 같은 소스를 CPU 세대별로 여러 번 빌드: 라이브러리 이름은 모듈이 지정 (기본 llama_jni)
if(NOT MN_LIB_NAME)
    set(MN_LIB_NAME llama_jni)
endif()

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${MN_ARCH_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${MN_ARCH_FLAGS} -O3")

set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)   # 추측 생성(common/speculative) 사용
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
# Adreno 650에서는 llama.cpp OpenCL 백엔드가 드라이버 크래시 → CPU 전용
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama_build)

add_library(${MN_LIB_NAME} SHARED ${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.cpp)
target_link_libraries(${MN_LIB_NAME} PRIVATE llama-common llama ggml-cpu android log)
target_link_options(${MN_LIB_NAME} PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -s)
set_target_properties(${MN_LIB_NAME} PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
