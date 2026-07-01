pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx 可选：jitpack（若不用本地 AAR）
        maven { url = uri("https://jitpack.io") }
        // voice 模块本地 AAR（sherpa-onnx 预编译包放 voice/libs/）
        flatDir { dirs("voice/libs") }
    }
}
rootProject.name = "Pophie"
include(":app")
include(":voice")
include(":voice-server")
include(":voicedemo")
