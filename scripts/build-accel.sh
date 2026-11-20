#!/bin/bash
# 가속 엔진(그래픽 칩 OpenCL + AI 칩 Hexagon NPU) 빌드.
# Qualcomm snapdragon-toolchain 이미지(안드로이드 NDK·OpenCL SDK·Hexagon SDK 포함)로 빌드해서
#   native-llama-gpu/prebuilt/arm64-v8a/libllama_jni_gpu.so   (앱이 그래픽 칩·AI 칩 지원 기기에서 불러오는 엔진)
#   app/src/main/assets/htp/libggml-htp-v73..v81.so          (AI 칩에서 도는 코드, 앱이 실행 시 저장소로 풀어 씀)
# 를 만든다. 이 파일들이 없으면 Gradle은 그래픽 칩(OpenCL)만 든 엔진을 직접 빌드한다.
# 이미지의 NDK(r29)가 앱(r28)과 달라 C++ 런타임은 엔진 안에 정적으로 넣는다 (c++_static).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
IMG=ghcr.io/snapdragon-toolchain/arm64-android:v0.8
docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/workspace" -w /workspace "$IMG" bash -lc '
set -e
# 지원 기기는 모두 ARMv8.6+ (i8mm) — 스냅드래곤 8 2세대 이상, 7s 3세대
ARCH="-march=armv8.6-a+fp16+dotprod+i8mm"
cmake -S native-llama/src/main/cpp -B build-accel -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH=$OPENCL_SDK_ROOT \
  -DHEXAGON_SDK_ROOT=$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT -DPREBUILT_LIB_DIR=android_aarch64 \
  -DMN_ARCH_FLAGS="$ARCH" -DMN_LIB_NAME=llama_jni_gpu -DMN_OPENCL=ON -DMN_HEXAGON=ON
cmake --build build-accel
'
mkdir -p "$ROOT/native-llama-gpu/prebuilt/arm64-v8a" "$ROOT/app/src/main/assets/htp"
cp "$ROOT/build-accel/libllama_jni_gpu.so" "$ROOT/native-llama-gpu/prebuilt/arm64-v8a/"
find "$ROOT/build-accel" -name 'libggml-htp-v*.so' -exec cp {} "$ROOT/app/src/main/assets/htp/" \;
ls -la "$ROOT/native-llama-gpu/prebuilt/arm64-v8a" "$ROOT/app/src/main/assets/htp"
