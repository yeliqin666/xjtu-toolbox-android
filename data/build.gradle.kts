// :data —— **数据层**：从 `:app` 摘出来的取数与会话代码，JVM 与 Android 共用同一份。
//
// 为什么必须是一个独立模块（不能塞进 :core）：业务取数用的是 okhttp + jsoup —— okhttp 只发布
// jvm / android 变体，jsoup 更是只有一份 JVM jar，两者都进不了 :core（它有三个目标，含 wasm）。
// 而 :core 的屏/ViewModel 又必须能在不碰网络实现的前提下被三端复用，所以切在「网络与解析」
// 这一层：`:core`（屏 / 逻辑 / 端口）+ `:data`（取数与解析）+ 三种承载。
//
// 当前（Stage 0 探针）里面只有**图书馆一条竖切**：`LibraryApi` / `LibraryPages`（读 + 写 +
// 解析 + 会话缝）。目的是回答「同一份 Kotlin 数据层能不能在 JVM 上跑通、输出与 Android 一致」——
// 答案由 `src/jvmTest` 里那份夹具契约测试给出。其余模块（33 个 *Api、会话内核、存储宿主实现）
// 按同样的五步法逐条搬，见 docs/desktop-port-plan.md §5.1。
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // 解析用 `:core` 的 `AppJson`（kotlinx.serialization）—— 不用 Android 平台那份 `org.json`：
    // 它在 JVM 单测里是 android.jar 的桩，一调就抛（`LibraryPages.parseSeatLayout` 早就踩过）。
    alias(libs.plugins.kotlin.serialization)
    // 场馆滑块验证码（`SliderCaptchaView`）从 `:app` 搬进这里（Stage B：滑块共享化）：
    // 那份是 @Composable，所以数据层第一次需要 Compose 编译器与 UI 依赖。
    // ⚠️ `:data` 只有 jvm+android 两个目标（加 wasm/ios 会全线崩），这与 Compose 不冲突。
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    // Android 目标：`:app` 消费这一份（同一份取数代码，行为不变）。
    android {
        namespace = "com.xjtu.toolbox.data"
        compileSdk {
            version = release(37) { }
        }
        minSdk = 31
    }

    // 桌面目标：窗口模式与 serve 模式都跑在这上面（`docs/desktop-port-plan.md` §3）。
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api 而不是 implementation：这两个库的类型出现在**公开签名**里 ——
            // `LibrarySession` 收发 okhttp 的 Request/Response，`LibraryPages.parseBookingCard`
            // 收 jsoup 的 Document。消费方必须能在自己的编译类路径上看到它们。
            api(libs.okhttp)
            api(libs.jsoup)
            // 同样是 api：`SessionExpiredException` 实现的 `SessionExpiredFailure` 在 :core，
            // 消费方按它判断「要不要静默重登」，得看得见这个父类型。
            api(project(":core"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // `SessionBackend` 的 BrotliInterceptor（校园网某些接口只发 br 压缩）。
            implementation(libs.okhttp.brotli)
            implementation(libs.okhttp.java.net.cookiejar)
            // 会话内核的「Ktor 写法」出口（`SiteSession.sendWithReAuth` →
            // `asToolboxKtorClient`）。引擎是 OkHttp（预置实例），所以这里是 JVM/Android
            // 都有的那份；`:data` 只有这两个目标，放 commonMain 是安全的（与 jsoup 同理：
            // ⚠️ 一旦给 `:data` 加 wasm/ios 目标，这两条立刻编不过）。
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // ── 场馆滑块验证码（Stage B）：`SliderCaptchaView` 是 @Composable，且用 miuix
            //    的 Text/主题 —— 与 `:core` 同一套坐标（CMP 的 android 变体转发到
            //    androidx.compose，与 :app 不重复类）。公开签名只涉及 :core 的模型，
            //    所以这里 implementation 就够（消费方经由 api(project(":core")) 已经有 compose）。
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.miuix.ui.kmp)
            // 识别器与滑块视图都要解 base64 → 原始像素/可绘制位图：两条缝在 :core
            // 的 `platform/ImageDecode.kt`（JVM 用 ImageIO、Android 用 BitmapFactory）。
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // 假上游（图书馆座位系统 + 可选的统一认证）从这里**搬走了**：它现在是 `:testkit`
            // 这个独立模块——因为桌面端的离屏渲染证据也要看同一批页面原文，而跨模块共享不了
            // test 源集（见 testkit/build.gradle.kts 的 KDoc）。搬动本身零成本：两份夹具只依赖 JDK。
            implementation(project(":testkit"))
        }
        androidMain.dependencies {
            // Android 侧的密文存储：`SecurePrefs`（EncryptedSharedPreferences）就靠它。
            implementation(libs.security.crypto)
        }
    }
}
