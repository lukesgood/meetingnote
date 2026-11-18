plugins {
    id("com.android.library")
}

// 가속 명령어(dotprod/fp16)가 없는 ARMv8.0 CPU용 기본판 (Cortex-A53/A73: Helio P/G35, Snapdragon 6xx 일부, Exynos 9611 등).
// 소스는 :native-whisper와 같고 컴파일 옵션과 라이브러리 이름만 다르다. 어느 쪽을 쓸지는 실행 시 CPU를 보고 고른다.
android {
    namespace = "com.meetingnote.whisper.base"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DMN_ARCH_FLAGS=-march=armv8-a",
                    "-DMN_LIB_NAME=whisper_jni_base",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../native-whisper/src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}
