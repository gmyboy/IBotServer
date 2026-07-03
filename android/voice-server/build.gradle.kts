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

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    api(project(":voice"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5") {
        exclude(group = "org.eclipse.paho", module = "org.eclipse.paho.android.service")
    }
}
