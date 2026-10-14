// :web —— 第一个**真正吃 :core** 的非 Android 端。
//
// 它不是新的一套业务代码：数据模型、Ktor 客户端、会话存储全来自 :core，
// 这里只放「浏览器外壳 + 屏幕」。存在的意义有两个：
//   1. 证明共享层在非 JVM 端真的能跑真数据（不是「能编译」而已）；
//   2. 给 :core 的每一次搬迁提供第二个编译目标，任何 JVM/Android-only 的东西都会立刻红。
//
// 目标只有 wasmJs（浏览器）。桌面端要出时补 jvm("desktop") 即可。
import java.io.File
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
            // S8：`wasmJsTest` 的浏览器环境。**`browser()` 而不是 `nodejs()`** —— `core/build.gradle.kts:44-56`
            // 记着当初 :core 没声明 wasm 测试环境的原因：一旦依赖 Compose 的 ui-graphics，Node 版测试包就会去
            // `import` `skiko.mjs`，而 Compose 的 Skia 绑定只为 browser 目标配好了 npm 解析 ⇒ nodejs 下
            // `ERR_MODULE_NOT_FOUND`。这一端比 :core 更离不开 browser：`:web` 的 wasmJsMain 自己就吃 Compose + MIUIX
            //（`:web` 是浏览器端，本来也没有“在 Node 里跑”这回事）。
            //
            // 跑法：`./gradlew :web:wasmJsBrowserTest`（karma + webpack，跑在真浏览器里）。
            // ⚠️ 2.4.20 里**新**那套 `browser { test { chromium(…) } }`（playwright 驱动）对 wasmJs 目标直接报错
            //（实测：`e: ❌ The new test {} DSL is currently not supported for wasmJs targets`）⇒ wasmJs 走的是
            // **旧**那一套 `testTask { useKarma { … } }`（任务描述自己写着 "Run all wasmJs tests inside browser
            // using karma and webpack"）。浏览器可执行文件**不钉机器路径**（仓库里不放 `/home/…` 那种东西）：
            // using karma and webpack"）。浏览器可执行文件**不钉机器路径**（仓库里不放 `/home/…` 那种东西），
            // 解析顺序：`-Pchrome.bin=<路径>` → 环境变量 `CHROME_BIN` → **本机常见位置自动探测**
            //（`/usr/bin/chromium*` / `/usr/lib/chromium*` / `google-chrome*` / `/snap/bin/chromium` /
            // `~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome`）→ 都没找到才交给
            // karma-chrome-launcher 自己找。**门禁自足**：新人 clone 下来直接跑，不用先知道这台机器的 Chrome 在哪。
            testTask {
                val chromeBin = providers.gradleProperty("chrome.bin").orNull
                    ?: providers.environmentVariable("CHROME_BIN").orNull
                    ?: listOf(
                        "/usr/bin/chromium",
                        "/usr/bin/chromium-browser",
                        "/usr/lib/chromium/chromium",
                        "/usr/bin/google-chrome",
                        "/usr/bin/google-chrome-stable",
                        "/snap/bin/chromium",
                    ).firstOrNull { File(it).canExecute() }
                    ?: File(System.getProperty("user.home"), ".cache/ms-playwright")
                        .listFiles { f -> f.isDirectory && f.name.startsWith("chromium") }
                        ?.sortedByDescending { it.name }
                        ?.map { File(it, "chrome-linux64/chrome") }
                        ?.firstOrNull { it.canExecute() }
                        ?.absolutePath
                if (chromeBin != null) environment("CHROME_BIN", chromeBin)
                useKarma {
                    // `--no-sandbox`：这台机器是 Ubuntu 24.04，AppArmor 关掉了非特权 user namespace
                    // ⇒ Chromium 自己报 "No usable sandbox!" 并 SIGABRT（日志里那 16 帧栈就是它）。
                    // 这不是“碰运气”：Chromium 的报错原文就建议用 `--no-sandbox` 当立刻可用的绕法。
                    // CI（GitHub runner）上这个 launcher 同样能跑（代价只是少一层隔离）。
                    useChromeHeadlessNoSandbox()
                }
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

        // ── wasmJsTest（D14 / 工单 S8）────────────────────────────────────────────────
        //
        // 这一端从 2026-10-13 起就有屏、有真数据，却一直没有自动化验收（`docs/desktop-port-plan.md` §15.6.1
        // 如实记着：“`:web` 没有测试源集”），能断言的只有“能编译 + 产物能出”。这一批把它补上：
        // A5 的**第三端形态**（同一份夹具在本端 import 一次）与“路由清单 = 分支”。
        //
        // 测试跑在**真浏览器**里（见上面 browser{} 那段）：这也让 `:core` 的 wasm-only actual
        // （localStorage 键值缝 / 档案投影）第一次有运行时证明，而不是只吃编译门禁。
        //
        // 夹具（A5）从哪来：`:core` 的 **commonTest**（`archive/ArchiveFixture.kt`）—— 与 `:core:jvmTest`
        // 消费的是**同一份文件**（规格要求，见 `docs/storage-layout.md` §7 的 A5 行），由文件末尾那个
        // `materializeArchiveFixture` 拷进本端测试源集。**不是另写一份夹具**：改了那份文件，这里跟着重生成。
        //
        // ⚠️ 为什么不是直接依赖 `:core` 的 wasmJs 测试 klib（那样“复用”最直，也最先试的）：`:core` 的
        // commonTest **整体**在 wasmJs 上编不过 —— 实测 `:core:compileTestKotlinWasmJs` 4 个 error／2 个文件：
        //  - `media/DownloadManagerTest.kt` 用 `runBlocking`：它在 wasmJs 上不可用（coroutines 1.11 的 wasm klib
        //    里那个声明是 HIDDEN，报成 “Unresolved reference”；那端的 event loop 自己写着
        //    “runBlocking event loop is not supported”）；
        //  - `schedule/JwxtChangesTest.kt` 用 `String(CharArray)`：在 wasm 上是 ERROR 级弃用
        //    （要写 `CharArray.concatToString()`）。
        // 修它们要动 `:core` 的既有测试（超出本工单的授权）⇒ 本端只把那**一个文件**编进来。
        // 这一条已写进交付报告，等有人真去建 `:core` 的 wasm 测试环境时一并收。
        wasmJsTest.dependencies {
            // kotlin.test（浏览器里跑，失败信息走 karma 的 console）
            implementation(kotlin("test"))
            // 被测的本体：`:core`（共享屏 / 模型 / 档案层）
            implementation(project(":core"))
        }
    }
}

// ── S8：wasmJsTest 要断言的**源码快照** ──────────────────────────────────────────
//
// `WebRouteCoverageTest` 与桌面那份 `DesktopRouteCoverageJvmTest` 同型：断言的对象是**源码文本**
//（`AppRoute.kt` 的声明、`ToolboxWebApp.kt` 里 `AppPage` 的顶层分支、`WebSupport.kt` 文件头里的如实说明）。
// 桌面端用 `java.io.File` 现读 —— 浏览器里**没有文件系统**，wasm 上也没有那个类型
// ⇒ 构建期读一次、生成一个 Kotlin 常量对象给测试用。
//
// 三份文件都声明在 `inputs` 里 ⇒ 改了源码就会重新生成（否则测试会对着旧快照断言，那是假绿）。
//
// ⚠️ 这个任务体里**不引用脚本成员**（不调脚本级函数、不读脚本级 val）：
// Gradle 的 configuration cache 序列化不了「脚本对象引用」，那样会直接以
// `cannot serialize Gradle script object references` 失败（本机实测）。
// 所以文件清单先落成一个**局部** val，转义/切块那几个函数也定义在 doLast 里面。
val webTestSourceSnapshotFiles = listOf(
    rootProject.file("core/src/commonMain/kotlin/com/xjtu/toolbox/nav/AppRoute.kt"),
    file("src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSupport.kt"),
    file("src/wasmJsMain/kotlin/com/xjtu/toolbox/web/ToolboxWebApp.kt"),
)

val generateWebTestSourceSnapshot = tasks.register("generateWebTestSourceSnapshot") {
    group = "verification"
    description = "把测试要断言的几份源码读成 Kotlin 常量（S8：浏览器里没有文件系统）"
    val sourceFiles: List<File> = webTestSourceSnapshotFiles
    inputs.files(sourceFiles)
        .withPropertyName("webTestSourceSnapshot")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    val outDir = layout.buildDirectory.dir("generated/webTestSourceSnapshot/kotlin").get().asFile
    outputs.dir(outDir)
    doLast {
        // 文件名 → 常量名（ToolboxWebApp.kt ⇒ TOOLBOX_WEB_APP）
        fun constantName(fileName: String): String =
            fileName.removeSuffix(".kt")
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .uppercase()

        // 按行切块：避免一个几十 KB 的字符串字面量（也避免在非 BMP 字符中间切断）
        fun chunks(text: String, maxChars: Int = 8_000): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            text.split('\n').forEachIndexed { index, line ->
                val piece = if (index == 0) line else "\n$line"
                if (current.isNotEmpty() && current.length + piece.length > maxChars) {
                    out += current.toString()
                    current.clear()
                }
                current.append(piece)
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        // 源码原文 → Kotlin 字符串字面量（这几份源码里有 `"`、`$`、反斜杠，逐个转义）
        fun literal(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }

        val body = buildString {
            appendLine("package com.xjtu.toolbox.web.testsources")
            appendLine()
            appendLine("// 由 `:web` 的 `generateWebTestSourceSnapshot` 生成，**不要手改**（见 web/build.gradle.kts）。")
            appendLine("// 值就是仓库里那几份源码的原文：浏览器里没有文件系统，测试读不到盘上的文件。")
            appendLine()
            appendLine("internal object WebSourceSnapshot {")
            sourceFiles.forEach { sourceFile ->
                appendLine()
                appendLine("    /** ${sourceFile.parentFile.name}/${sourceFile.name} 的原文（生成时的字节）。 */")
                val literals = chunks(sourceFile.readText()).map { literal(it) }
                appendLine("    val ${constantName(sourceFile.name)}: String = ${literals.joinToString(" +\n        ")}")
            }
            appendLine("}")
        }
        val pkgDir = File(outDir, "com/xjtu/toolbox/web/testsources").apply { mkdirs() }
        File(pkgDir, "WebSourceSnapshot.kt").writeText(body)
    }
}

// ── A5 的夹具：`:core` 的 commonTest 里那**一个文件**（不另写一份） ──────────────────
//
// `docs/storage-layout.md` §7 的 A5 要求「同一份档案夹具在三端各 import 一次」，夹具的真源住在
// `:core` 的 commonTest（不能放 `:testkit`：那模块是 `kotlin.jvm`，wasm 消费不了）。本端拿它的办法是
// 把**那份源文件**拷进生成目录、再当本端测试源集的一个源根 —— 内容是同一份（改了那份文件，这里会
// 跟着重新生成；`Copy` 任务的输入输出就是这三份里的那一份）。
//
// ⚠️ 为什么不是直接把 `:core` 的 wasmJs 测试 klib 拉过来（那样“复用”最直）：`:core` 的 commonTest
// **整体**在 wasmJs 上编不过（实测 `:core:compileTestKotlinWasmJs` 4 个 error／2 个文件：
// `media/DownloadManagerTest.kt` 的 `runBlocking`（wasmJs 上不可用）、`schedule/JwxtChangesTest.kt` 的
// `String(CharArray)`（wasm 上是 ERROR 级弃用））—— 修那些要动 `:core` 的既有测试，超出本工单的授权。
val materializeArchiveFixture = tasks.register<Copy>("materializeArchiveFixture") {
    group = "verification"
    description = "把 :core commonTest 里的档案夹具拷进本端测试源集（A5：三端同一份夹具）"
    from(rootProject.file("core/src/commonTest/kotlin/com/xjtu/toolbox/archive/ArchiveFixture.kt"))
    into(layout.buildDirectory.dir("generated/coreArchiveFixture/kotlin/com/xjtu/toolbox/archive"))
}

// 两个生成目录一起进 wasmJsTest 的源集（`srcDir` 传任务 ⇒ 编译自动依赖生成任务）。
kotlin.sourceSets.getByName("wasmJsTest").kotlin.apply {
    srcDir(generateWebTestSourceSnapshot)
    srcDir(materializeArchiveFixture)
}
