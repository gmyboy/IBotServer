plugins {
    id("com.android.library")
}

android {
    namespace = "com.pophie.voice"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ML 模型若打进 assets，禁止压缩，避免 mmap 对齐损坏
    androidResources {
        noCompress.addAll(listOf("onnx", "bin", "ort"))
    }
}

dependencies {
    // sherpa-onnx 预编译 AAR（含 Silero VAD + 说话人 embedding + 自带 onnxruntime 原生库）。
    // 放置 voice/libs/sherpa-onnx.aar（见 voice/README）。settings.gradle.kts 已配 flatDir。
    implementation(":sherpa-onnx@aar")
    // sherpa-onnx 的 Java 接口由 Kotlin 编写，运行期需要 kotlin-stdlib。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("androidx.annotation:annotation:1.7.1")
    // 备选（不想用本地 AAR 时，注释掉上面一行）：jitpack 拉同一个 AAR
    // implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")

    testImplementation("junit:junit:4.13.2")
}
