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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
