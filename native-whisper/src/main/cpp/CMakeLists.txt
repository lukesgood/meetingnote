cmake_minimum_required(VERSION 3.22)
project(whisper_jni C CXX)

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

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper android log)
# ggml 심볼을 숨겨 llama 모듈의 ggml과 충돌하지 않도록 함
target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -s)
set_target_properties(whisper_jni PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
