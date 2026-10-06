// :web —— 第一个**真正吃 :core** 的非 Android 端。
//
// 它不是新的一套业务代码：数据模型、Ktor 客户端、会话存储全来自 :core，
// 这里只放「浏览器外壳 + 屏幕」。存在的意义有两个：
//   1. 证明共享层在非 JVM 端真的能跑真数据（不是「能编译」而已）；
//   2. 给 :core 的每一次搬迁提供第二个编译目标，任何 JVM/Android-only 的东西都会立刻红。
//
// 目标只有 wasmJs（浏览器）。桌面端要出时补 jvm("desktop") 即可。
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    // 版本都在根工程钉死（KGP / CMP 都在 classpath 上），子工程只 bare id 引用。
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "toolbox-web"
        browser {
            commonWebpackConfig {
                outputFileName = "toolbox-web.js"
            }
            // 开发服务器的 host/port/proxy 不走 DSL（2.4.20 的 `wasmJs { browser { } }` 里没有
            // `devServer`），改由 `webpack.config.d/dev-server.js` 合并进 webpack 配置。
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // 共享层：模型 / Ktor 客户端 / 会话存储全在这里
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // MIUIX：与 :app 同一套组件库（Apache-2.0）。用**多平台**坐标（无 -android 后缀），
            // Gradle Module Metadata 会自动解析到 wasm-js 变体 ⇒ 观感与 Android 端是结构性一致的，
            // 不是模仿。:app 用的是 miuix-ui-android，两者同源同版本。
            implementation(libs.miuix.ui.kmp)
            implementation(libs.miuix.icons.kmp)
        }
    }
}
