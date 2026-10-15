import java.io.File
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 跳一跳的 9 张地标图放 :web 而不是 :core：⚠️ AGP 9 KMP 库插件下 CMP 1.12.1 不把 commonMain/composeResources 打进 Android assets。
compose.resources {
    publicResClass = true
    packageOfResClass = "com.xjtu.toolbox.web.res"
    // 本模块 Auto 不会生成 Res 类，只能显式要求总是生成。
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
            // 浏览器跑 wasmJsTest（nodejs 下 skiko.mjs 解析失败）；⚠️ 2.4.20 新的 `test {}` DSL 不支持 wasmJs，用 karma。Chrome：`-Pchrome.bin` → `CHROME_BIN` → 常见位置探测。
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
                    // `--no-sandbox`：Ubuntu 24.04 的 AppArmor 关掉了非特权 user namespace，Chromium 会 SIGABRT。
                    useChromeHeadlessNoSandbox()
                }
            }

            // 2.4.20 的 DSL 没有 `devServer`，开发服务器配置在 `webpack.config.d/dev-server.js`。
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.miuix.ui.kmp)
            implementation(libs.miuix.icons.kmp)
        }

        // ── wasmJsTest：只编进 `:core` commonTest 的 ArchiveFixture.kt（:core 的 commonTest 整体在 wasmJs 上编不过）──
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core"))
        }
    }
}

// ── 测试要断言的源码快照（浏览器里没有文件系统）；⚠️ 任务体里不能引用脚本成员，否则 configuration cache 失败 ──
val webTestSourceSnapshotFiles = listOf(
    rootProject.file("shared/navigation/src/commonMain/kotlin/com/xjtu/toolbox/nav/AppRoute.kt"),
    file("src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSupport.kt"),
    file("src/wasmJsMain/kotlin/com/xjtu/toolbox/web/ToolboxWebApp.kt"),
    rootProject.file("app/src/main/assets/games/merge/index.js"),
    rootProject.file("server/src/main/kotlin/com/xjtu/toolbox/server/ServeAgentTools.kt"),
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
        fun constantName(fileName: String): String =
            fileName.substringBeforeLast('.')
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .replace(Regex("[^A-Za-z0-9]+"), "_")
                .uppercase()
        // 按行切块：避免超长字符串字面量，也避免切断非 BMP 字符
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

// ── 档案夹具：拷 `:core` commonTest 的那一份（`:testkit` 是 jvm，wasm 用不了）──
val materializeArchiveFixture = tasks.register<Copy>("materializeArchiveFixture") {
    group = "verification"
    description = "把 :core commonTest 里的档案夹具拷进本端测试源集（A5：三端同一份夹具）"
    from(rootProject.file("core/src/commonTest/kotlin/com/xjtu/toolbox/archive/ArchiveFixture.kt"))
    into(layout.buildDirectory.dir("generated/coreArchiveFixture/kotlin/com/xjtu/toolbox/archive"))
}

kotlin.sourceSets.getByName("wasmJsTest").kotlin.apply {
    srcDir(generateWebTestSourceSnapshot)
    srcDir(materializeArchiveFixture)
}

val mergeGameResources = tasks.register<Sync>("mergeGameResources") {
    group = "build"
    description = "把 :app 的 assets/games/merge 拷进 :web 的资源根（D12 ⑤）"
    from(rootProject.file("app/src/main/assets/games/merge")) { into("games/merge") }
    into(layout.buildDirectory.dir("generated/mergeGameResources"))
}

kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(mergeGameResources)
