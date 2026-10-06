import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// :core —— 三端共用的业务逻辑与平台抽象（KMP）。
//
// 当前处于「传输层探针」阶段，目标只有一个：回答一个目前没有现成答案的问题 ——
// 把 okhttp（实测只发布 jvm / android 变体）换成 Ktor 之后，Android 之外的端能不能
// 真的跑通「共享 CookieJar + Json 反序列化 + 真实上游数据」。
//
// 所以这里刻意只声明 jvm / wasmJs；androidLibrary 目标等探针结论出来再补，
// 免得一上来就在 AGP 9 的 KMP 插件上纠缠——那属于「已验证可行」的工程活，不是未知项。
plugins {
    // 不带版本：版本在根工程 build.gradle.kts 里钉死（KGP 已在 classpath，
    // 子工程再带版本会报「已在 classpath with an unknown version」）
    id("org.jetbrains.kotlin.multiplatform")
    // AGP 9 的 KMP Android 库插件。同样不带版本（根工程已 apply false 钉死）。
    // 没有它 :app 就消费不了 :core：KMP 的 jvm 变体对 Android 消费者不可见（平台属性不匹配）。
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 与 :app 对齐（Java/Kotlin 目标 21）：miuix 各模块都是 JDK 21 工具链编译的，
    // miuix-nav 的 entry<T>() 是 inline 函数，字节码目标不一致会直接编译失败。
    jvmToolchain(21)

    // Android 目标。写法照 miuix（AGP 9 的 KMP 库插件是 `android { }`，不是旧的
    // `androidLibrary { }`）；参数与 :app 对齐：compileSdk 37 / minSdk 31。
    android {
        namespace = "com.xjtu.toolbox.core"
        compileSdk {
            version = release(37) { }
        }
        minSdk = 31
    }

    jvm()

    // Web 端（Kotlin/Wasm）。
    //
    // 这里**不声明测试环境**（原来用 nodejs()），原因实测：一旦 :core 依赖了 Compose 的
    // ui-graphics（见 commonMain 里那条注释），Node 版测试包就会去 import `skiko.mjs` —— 
    // Compose 的 Skia 绑定只为 browser 目标配好了 npm 解析，nodejs 下报 ERR_MODULE_NOT_FOUND。
    //
    // 所以 wasm 的职责拆成两半，各自用更合适的形态守：
    //   - **可移植性门禁** = `compileKotlinWasmJs`（下面这个目标本身）。它把 android.* /
    //     androidx.*（除绘图基础类型）/ java.* / kotlin.jvm.* / okhttp 等一次拦下 ——
    //     比交接文档 §9 那条 grep 断言强，而且不依赖任何运行时。
    //   - **运行时证明** = `:web` 在**真浏览器**里跑真数据（比 Node 测试更接近真实场景）。
    // 共享逻辑的单元测试仍然在 `:core:jvmTest` 里跑全套（同一份 commonTest 源码）。
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // 库侧也要声明一个 JS 环境：:web 的产物打包会经由 umbrella 的 npm 安装任务，
        // 而它要求依赖图里每个 wasm 目标都“configured for JS usage”。
        // 用 browser 而不是 nodejs：nodejs 下测试包会去 import skiko.mjs 而 ERR_MODULE_NOT_FOUND
        //（Compose 的 Skia 绑定只为 browser 配好了 npm 解析），browser 没有这个问题。
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // api 而非 implementation：HttpClientEngine 出现在公开的 expect 声明里
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // 只放「无 Composable 的绘图基础类型」：Color / Path / geometry / unit。
            // 它们在 Compose Multiplatform 里是 common 的，所以 agent/bot、game 这些
            // 只用到绘图类型的文件可以进 commonMain（比交接文档 §10 的「:core 不含任何
            // androidx.compose 类型」放宽了一档，是明确拍板过的取舍）。
            // 用 api 而不是 implementation：搬进来的文件会把 Color/Path 暴露在公开 API 上。
            api(libs.compose.ui.graphics)
            // java.time 不是多平台的（在 JVM 上也是默认导入，所以 import 判据看不见）。
            // 用 api 而非 implementation：CourseTable.termStart 是公开的 LocalDate，
            // 消费方（:web / 将来的 :platform）必须能在自己的编译类路径上看到这个类型。
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // 离线验证 Cookie 往返：不依赖真服务器，三端都能跑，可进 CI 门禁
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            // 探针阶段沿用 App 现在的引擎，好让「换 Ktor」的差异只在 Ktor 这一层，
            // 不掺进引擎差异。iOS 用 darwin、Web 用 js，等对应目标加进来再各自 actual。
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}
