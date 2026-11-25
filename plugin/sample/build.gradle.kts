// :plugin:sample —— 一个可安装的示例插件 APK。
//
// 构建：./gradlew :plugin:sample:assembleDebug
// 产物：plugin/sample/build/outputs/apk/debug/sample-debug.apk
// 在 App 的「设置 → 插件」里选这个 APK 安装即可。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xjtu.toolbox.plugin.sample"
    compileSdk {
        version = release(37) {
        }
    }
    defaultConfig {
        applicationId = "com.xjtu.toolbox.plugin.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        release {
            // 真插件应该开 R8；样例只用来验证加载流程，debug 包也能被宿主接受。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}


dependencies {
    // 插件作者只需要这一个宿主 API；Compose / lifecycle 等由插件自己打包。
    implementation(project(":plugin:api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.core)
}
