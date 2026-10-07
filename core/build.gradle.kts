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
    // 第 3 步（按屏搬 UI 进 commonMain）需要 Compose 的**完整 UI 栈**与 Compose 编译器。
    // 版本同样在根工程钉死；CMP 1.12.1 与 :app 的 compose ui 1.12.1 同版。
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
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
            // ── 第 3 步的使能层：Compose 的**完整 UI 栈**接进来 ──────────────────
            // 交接文档 §10 原本写「:core 里不要混任何 androidx.compose 类型」，那条针对的是
            // **第 1 步**（让 :core 保持纯净，鸿蒙若走非 Compose 方案不返工）。第 3 步要求
            // 「按屏搬 UI 进 commonMain」，所以 UI 类型必须进来 —— 这是明确拍板过的取舍：
            // 若鸿蒙最终选 Kuikly（非 Compose），搬进来的 UI 层要返工，非 UI 层不受影响。
            //
            // 这些坐标都是 Compose Multiplatform 的：android 变体是空壳转发到 androidx.compose，
            // 所以与 :app 的 compose BOM 不会重复类（版本同为 1.12.1）。
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.animation)
            // MIUIX：与 :app 同一套组件库（同版本、同源）。用 KMP 根模块（无 -android 后缀）。
            api(libs.miuix.ui.kmp)
            api(libs.miuix.icons.kmp)
            api(libs.miuix.nav.kmp)
            api(libs.miuix.preference.kmp)
            api(libs.miuix.squircle.kmp)
            // JetBrains 版 lifecycle：ViewModel / viewModelScope / collectAsStateWithLifecycle。
            // 与 :app 的 androidx.lifecycle 同版（2.11.0），只换组名不换版本。
            api(libs.jb.lifecycle.viewmodel)
            api(libs.jb.lifecycle.viewmodel.compose)
            api(libs.jb.lifecycle.runtime.compose)
            // 玻璃质感（Kyant backdrop）：**全 KMP**（android/ios/js/wasm/desktop 都有变体），
            // 所以 ui/glass 那一层可以进 commonMain。:app 用的是同一个坐标。
            api(libs.kyant.backdrop)
            // 图标：`Icons.*` 与 :app 同一套（JetBrains 多平台版，含 wasmJs），
            // 所以搬进来的屏幕 import 一行都不用改。core 里有 BackButton 这种只用
            // 常见图标的屏，extended 里是 Forum/ThumbUp 这类。
            api(libs.compose.material.icons.core)
            // 跳端图标（Icons.*）：CMP 多平台版停在 1.7.3，见版本目录里的说明。
            api(libs.compose.material.icons.extended)
            // 多平台的 BackHandler（Android 专属的 androidx.activity.compose.BackHandler
            // 在共享 UI 里用不了）。android 变体委派给 androidx.activity，所以行为不变。
            api(libs.compose.ui.backhandler)
            // java.time 不是多平台的（在 JVM 上也是默认导入，所以 import 判据看不见）。
            // 用 api 而非 implementation：CourseTable.termStart 是公开的 LocalDate，
            // 消费方（:web / 将来的 :platform）必须能在自己的编译类路径上看到这个类型。
            api(libs.kotlinx.datetime)
            // ── 社区屏（community/CommunityChrome）搬进来带来的两个多平台依赖 ──────────
            // 社区壳用 Coil 加载头像、用 mikepenz 的 renderer 渲染 GFM。两个都是**全 KMP**
            // （android 变体转发到同名 androidx/okhttp 实现，与 :app 的依赖同版同源，不会重复类；
            // wasmJs 变体已在 Maven Central 上核实存在）。
            // ⚠️ 只声明不依赖 okhttp 的那些：coil 的 `coil-network-okhttp` 留在 :app（那是
            // Android 侧的取图引擎）。Web 端目前没注册取图引擎，远程图不显示 —— 属于已知降级。
            api(libs.coil.compose)
            api(libs.markdown.renderer)
            api(libs.markdown.renderer.coil3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // 离线验证 Cookie 往返：不依赖真服务器，三端都能跑，可进 CI 门禁
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // 形状形变加载器（ui/components/MorphingLoader 的 Android actual）。
            // 这个库**没有 KMP 发布**（只有 Android 变体），所以只能待在 androidMain ——
            // 正是它把 MorphingLoader 逼成了一个平台切口（其余端走圆环降级）。
            implementation(libs.androidx.graphics.shapes)
            // 系统栏外观（ui/theme/PlatformSystemBars 的 Android actual 用 WindowCompat）。
            // 与 :app 同一个坐标；compose.ui 的 android 变体虽然也会传递进来，但这里显式声明，
            // 免得将来 compose 换依赖时这个 actual 悄悄编不过。
            implementation(libs.androidx.core.ktx)
        }
        jvmMain.dependencies {
            // 探针阶段沿用 App 现在的引擎，好让「换 Ktor」的差异只在 Ktor 这一层，
            // 不掺进引擎差异。iOS 用 darwin、Web 用 js，等对应目标加进来再各自 actual。
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            // localStorage / window 等浏览器 API（KeyValueStore 的 Web 实现用）
            implementation(libs.kotlinx.browser)
        }
    }
}
