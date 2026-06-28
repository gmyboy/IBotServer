plugins {
    id("com.android.application")
}

android {
    namespace = "com.pophie.voice.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pophie.voice.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            // 调试含模拟器
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            // 发布只打 arm64，体积更小
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress.addAll(listOf("onnx", "bin", "ort"))
    }
}

dependencies {
    implementation(project(":voice"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
