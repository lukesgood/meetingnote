import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.meetingnote.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meetingnote.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 24
        versionName = "0.1.23"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Play 업로드 키: 저장소 밖(~/.meetingnote-signing/keystore.properties)에 둔다. 없으면 play 빌드는 서명되지 않음
    val uploadProps = Properties().apply {
        val f = file(System.getProperty("user.home") + "/.meetingnote-signing/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (uploadProps.containsKey("storeFile")) create("upload") {
            storeFile = file(uploadProps.getProperty("storeFile"))
            storePassword = uploadProps.getProperty("storePassword")
            keyAlias = uploadProps.getProperty("keyAlias")
            keyPassword = uploadProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // GitHub 배포·태블릿 설치용은 계속 debug 키 (키를 바꾸면 기존 설치본을 지워야 해 회의가 사라짐)
            signingConfig = signingConfigs.getByName("debug")
        }
        // Google Play 업로드용: ./gradlew :app:bundlePlay → app/build/outputs/bundle/play/app-play.aab
        create("play") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("upload")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // 안드로이드 org.json은 단위 테스트에서 동작하지 않음
    implementation(project(":native-whisper"))
    implementation(project(":native-llama"))
    runtimeOnly(project(":native-whisper-base"))
    runtimeOnly(project(":native-llama-base"))
    runtimeOnly(project(":native-llama-gpu"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
