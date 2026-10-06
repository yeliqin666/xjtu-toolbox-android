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
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 与 :app 对齐（Java/Kotlin 目标 21）：miuix 各模块都是 JDK 21 工具链编译的，
    // miuix-nav 的 entry<T>() 是 inline 函数，字节码目标不一致会直接编译失败。
    jvmToolchain(21)

    jvm()

    // Web 端（Kotlin/Wasm）。测试跑在 Node 上：MockEngine 不需要网络，
    // 于是「共享代码在非 JVM 目标上能编、能跑」就成了 CI 门禁，不依赖浏览器。
    // 浏览器特有的问题（CORS、localStorage、同源反代）留给下一步的 web 应用探针。
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // api 而非 implementation：HttpClientEngine 出现在公开的 expect 声明里
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // 离线验证 Cookie 往返：不依赖真服务器，三端都能跑，可进 CI 门禁
            implementation(libs.ktor.client.mock)
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
