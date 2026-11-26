// :plugin:sample —— 一个可安装的示例插件 APK。
//
// 构建：./gradlew :plugin:sample:assembleDebug
// 产物：plugin/sample/build/outputs/apk/debug/sample-debug.apk
//
// 注意这里**没有** Compose 依赖：插件只依赖 :plugin:api，界面由宿主渲染。
// 所以这个 APK 只有几百 KB，而不是带一整套 Compose 的 20 多 MB。
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "top.dzhz.plugin.sample"
    compileSdk {
        version = release(37) {
        }
    }
    defaultConfig {
        applicationId = "top.dzhz.plugin.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // 插件作者唯一需要依赖的东西。Compose / lifecycle / 协程都不用带。
    implementation(project(":plugin:api"))
}
