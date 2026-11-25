// :plugin:api —— 插件作者编译时依赖的唯一宿主 API。刻意保持"Java ABI 干净"：
// 只有 Android 框架类型，没有 suspend / 默认参数 / Kotlin 集合以外的类型，
// 这样 R8 改名宿主时插件仍能按原类名找到这些接口（见 app/proguard-rules.pro 与 docs/plugins.md）。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.xjtu.toolbox.plugin.api"
    compileSdk {
        version = release(37) {
        }
    }
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

