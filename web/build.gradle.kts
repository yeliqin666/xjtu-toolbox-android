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

// 多平台资源：跳一跳的 9 张地标图。
//
// **刻意放在 :web 而不是 :core**：AGP 9 的 KMP 库插件下，CMP 1.12.1 不会把
// `commonMain/composeResources` 接到 Android 变体的 assets 上（实测：
// `copyAndroidMainComposeResourcesToAndroidAssets` 的 outputDirectory 没人赋值，
// 打出来的 AAR 里也没有 assets/）⇒ 放 :core 会让 **Android 端取不到图**，那是行为变化。
// 所以同一份字节存两处：Android 继续用 `:app` 的 `R.drawable.hop_landmark_*`（逐字未变），
// Web 用这里的 composeResources。共享屏只收 `landmarkImages: List<ImageBitmap>`。
compose.resources {
    publicResClass = true
    packageOfResClass = "com.xjtu.toolbox.web.res"
    // Auto（默认）在这个模块里不会生成 Res 类：实测 :web 的
    // `generateResourceAccessorsFor*` / `generateComposeResClass` 的 onlyIf 判false（全 SKIPPED），
    // 同一个工程里 :core 却能生成 —— 差别不在资源目录，只能显式要求总是生成。
    generateResClass = always
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
            // 跳一跳的地标图（compose.resources 块在上面）
            implementation(compose.components.resources)
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
