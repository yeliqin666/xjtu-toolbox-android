pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "XJTUToolBox"
include(":app")
include(":wear")

includeBuild("miuix-ref") {
    dependencySubstitution {
        substitute(module("top.yukonga.miuix.kmp:miuix-ui-android")).using(project(":miuix-ui"))
        substitute(module("top.yukonga.miuix.kmp:miuix-preference-android")).using(project(":miuix-preference"))
        substitute(module("top.yukonga.miuix.kmp:miuix-icons-android")).using(project(":miuix-icons"))
        // 超椭圆圆角（MIUI/iOS 那种平滑拐角）。设备不支持 RuntimeShader 时库内部
        // 自动退回普通 RoundedCornerShape，minSdk 31 上安全。
        substitute(module("top.yukonga.miuix.kmp:miuix-squircle-android")).using(project(":miuix-squircle"))
    }
}
