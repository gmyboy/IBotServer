plugins {
    id("com.android.library")
}

android {
    namespace = "com.pophie.voice.server"
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
}

dependencies {
    api(project(":voice"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
}
