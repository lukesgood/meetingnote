plugins {
    id("com.android.library")
}

// 가속 판 엔진 (llama_jni_gpu): 그래픽 칩(Adreno, OpenCL) + AI 칩(Hexagon NPU).
// 소스는 :native-llama와 같고, 그래픽 칩·AI 칩 지원 기기(스냅드래곤 8 2세대 이상 등)에서만 앱이 이 판을 불러온다.
// 이 판은 CPU로도 동작하므로 기기 점검에서 CPU·그래픽 칩·AI 칩을 비교해 가장 빠른 쪽을 쓴다.
//  - prebuilt/에 scripts/build-accel.sh로 만든 엔진(Hexagon SDK 필요)이 있으면 그것을 넣는다
//  - 없으면 여기서 그래픽 칩(OpenCL)만 든 엔진을 빌드한다
val prebuilt = file("prebuilt/arm64-v8a/libllama_jni_gpu.so").exists()

android {
    namespace = "com.meetingnote.llama.gpu"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        if (!prebuilt) externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DMN_ARCH_FLAGS=-march=armv8.6-a+fp16+dotprod+i8mm",   // 지원 기기는 모두 ARMv8.6+ (i8mm)
                    "-DMN_LIB_NAME=llama_jni_gpu",
                    "-DMN_OPENCL=ON",
                )
            }
        }
    }
    if (prebuilt) {
        sourceSets["main"].jniLibs.srcDir("prebuilt")
    } else {
        externalNativeBuild {
            cmake {
                path = file("../native-llama/src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }
}
