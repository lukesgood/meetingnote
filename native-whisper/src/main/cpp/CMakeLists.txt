cmake_minimum_required(VERSION 3.22)
project(whisper_jni C CXX)

# 같은 소스를 CPU 세대별로 여러 번 빌드: 라이브러리 이름은 모듈이 지정 (기본 whisper_jni)
if(NOT MN_LIB_NAME)
    set(MN_LIB_NAME whisper_jni)
endif()

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${MN_ARCH_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${MN_ARCH_FLAGS} -O3")

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(${MN_LIB_NAME} SHARED ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp)
target_link_libraries(${MN_LIB_NAME} PRIVATE whisper android log)
# ggml 심볼을 숨겨 llama 모듈의 ggml과 충돌하지 않도록 함
target_link_options(${MN_LIB_NAME} PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -s)
set_target_properties(${MN_LIB_NAME} PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
