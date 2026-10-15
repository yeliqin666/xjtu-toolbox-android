// build-logic —— 各 KMP 模块共用的约定插件（目标平台、compileSdk、wasm、测试落盘根）。
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
