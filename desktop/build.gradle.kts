import java.io.File
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// :desktop —— 桌面端（Linux / Windows），Compose Desktop 原生窗口；离屏渲染证据在 test 源集（依赖 `:testkit`，不能进交付物）。
/**
 * 桌面包的版本号（jpackage 的 `--app-version`）。⚠️ 必须与 `:app` 的 `versionName` 逐字相同：
 * 两个手抄本会漂（App 5.2.0 / 安装包 5.1.1 就是这么来的），`DesktopPackageVersionJvmTest` 盯着这件事。
 */
val desktopPackageVersion = "5.2.0"

/** 反馈后端的凭据（与 `:app` 同一份来源） */
val desktopFeedbackConfig: Map<String, String> = run {
    val fp = rootProject.file("feedback.properties")
    val props = Properties().apply { if (fp.exists()) fp.inputStream().use { load(it) } }
    fun cfg(key: String): String {
        val underscored = key.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
        val glued = key.uppercase()
        listOf("FEEDBACK_$underscored", "FEEDBACK_$glued").distinct().forEach { name ->
            System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return props.getProperty(key)?.trim().orEmpty()
    }
    mapOf(
        "APP_ID" to cfg("appId"),
        "APP_SECRET" to cfg("appSecret"),
        "BASE_TOKEN" to cfg("baseToken"),
        "TABLE_SUBMIT" to cfg("tableSubmit"),
        "TABLE_REPLY" to cfg("tableReply"),
    )
}

/** 把上面那份凭据生成成一个 Kotlin 常量对象（`DesktopBuildConfig`） */
val generateDesktopBuildConfig = tasks.register("generateDesktopBuildConfig") {
    val outDir = layout.buildDirectory.dir("generated/desktopBuildConfig/kotlin").get().asFile
    val values = desktopFeedbackConfig
    val version = desktopPackageVersion
    inputs.property("feedback", values)
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val pkgDir = File(outDir, "com/xjtu/toolbox/desktop").apply { mkdirs() }
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val text = buildString {
            appendLine("package com.xjtu.toolbox.desktop")
            appendLine()
            appendLine("// 由 `:desktop` 的 `generateDesktopBuildConfig` 任务生成，**不要手改**：")
            appendLine("// 值来自仓库根目录的 `feedback.properties`（gitignored）或环境变量，")
            appendLine("// 与 `:app` 的 `BuildConfig.FEEDBACK_*` 同一份来源（见 desktop/build.gradle.kts）。")
            appendLine("internal object DesktopBuildConfig {")
            appendLine("    const val VERSION_NAME = \"${esc(version)}\"")
            values.forEach { (key, value) ->
                appendLine("    const val FEEDBACK_$key = \"${esc(value)}\"")
            }
            appendLine("}")
        }
        File(pkgDir, "DesktopBuildConfig.kt").writeText(text)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // JVM 21（与 miuix 对齐）。
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir(generateDesktopBuildConfig)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // 桌面的 `Dispatchers.Main`（viewModelScope 要用），Compose Desktop 自己不带。
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // ── test：离屏渲染证据与桌面装配验收（假上游 `:testkit` 不进任何交付物）──
    testImplementation(project(":testkit"))
    testImplementation(kotlin("test"))
    // JUnit4：kotlin-jvm 上 `kotlin("test")` 要显式给引擎。
    testImplementation(libs.junit)
    // 黄页是 Ktor 接口 ⇒ 用 MockEngine 喂假响应。
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.zxing.core)
}

/** `./gradlew :desktop:run` —— 真窗口。 */
compose.desktop {
    application {
        mainClass = "com.xjtu.toolbox.desktop.MainKt"
        // ⚠️ Windows 的包只能在 Windows 上打（jpackage 不能交叉构建，要 WiX）；见 `.github/workflows/desktop-windows.yml`。
        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.AppImage,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "xjtu-toolbox"
            // jpackage 只接受 x.y.z 形式的版本号。
            packageVersion = desktopPackageVersion
            // ⚠️ 只能用 ASCII：jpackage 在 Windows 上固定 `-cultures:en-us` ⇒ MSI 代码页 1252，
            // 中文会让 WiX 的 light.exe 报 `LGHT0311`（退出码 311，且不打印任何错误）⇒ CI 上随机失败。
            // 将来若要中文，得绕开 Compose 插件直接给 jpackage 传 `--resource-dir`（塞一份 overrides.wxi
            // 把 `Product/@Codepage` 设成 936/65001）——那个口子插件目前没暴露。
            // （Linux 的 .deb / .AppImage 描述读的也是同一个值，会跟着变英文；DSL 没有分平台的 description。）
            description = "XJTU ToolBox (desktop preview)"
            vendor = "XJTU ToolBox"
            copyright = "Copyright © XJTU ToolBox contributors"

            // jlink 要收进运行时的那套 JDK 模块。插件自带的只有 java.base / java.desktop /
            // java.logging / jdk.crypto.ec 四个，其余靠 jdeps 看完字节码去猜。
            // 这里的回环代理直接用 `com.sun.net.httpserver.HttpServer`（media/LoopbackStreamProxy.kt），
            // 不赌 jdeps 的自动建议 —— 漏了不会编译失败，只会在**装好的包**里「取不到流」，
            // 而那台机器上没 JDK、看不到 stacktrace。重复点名无害（jlink 多给 --add-modules 是并集）。
            modules("jdk.httpserver")

            linux {
                // 应用自己的图标（由自适应图标矢量光栅化，见 docs/linux-delivery.md §2.1）；⚠️ 不要用 `mipmap-*/ic_launcher.webp`，那是模板占位图。
                iconFile.set(project.file("icons/linux.png"))
                menuGroup = "Education"
                appCategory = "Education"
                // ⚠️ jpackage 的 `--linux-deb-maintainer` 只收邮箱，自己会拼成 `<vendor> <邮箱>`。
                debMaintainer = "114236693+Triangle-GitHub@users.noreply.github.com"
            }

            /** Windows 那一套 jpackage `--win-*` / WiX 参数。 */
            windows {
                perUserInstall = true
                upgradeUuid = "528a294e-23ee-4307-a37a-1a71bb591fa0"
                menuGroup = "Education"
                iconFile.set(project.file("icons/windows.ico"))
            }
        }
    }
}

/** `./gradlew :desktop:renderScreens` —— 离屏把 :core 的屏渲染成 PNG。 */
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "把 :core 的屏离屏渲染成 PNG（无需显示服务器；含真登录后的图书馆）"
    mainClass.set("com.xjtu.toolbox.desktop.RenderScreensKt")
    classpath = sourceSets["test"].runtimeClasspath
    args = listOf(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

tasks.register<JavaExec>("renderHomePreview") {
    group = "verification"
    description = "Render the shared home screen with offline fixtures at phone and desktop sizes"
    mainClass.set("com.xjtu.toolbox.desktop.RenderHomePreviewKt")
    classpath = sourceSets["test"].runtimeClasspath
    args = listOf(layout.buildDirectory.dir("home-preview").get().asFile.absolutePath)
}

tasks.register<JavaExec>("renderInterfaceRefinements") {
    group = "verification"
    mainClass.set("com.xjtu.toolbox.desktop.RenderInterfaceRefinementsKt")
    classpath = sourceSets["test"].runtimeClasspath
    args = listOf(layout.buildDirectory.dir("interface-preview").get().asFile.absolutePath)
}

tasks.register<JavaExec>("renderCampusExperience") {
    group = "verification"
    description = "Render offline previews of the meal report and notification filters"
    mainClass.set("com.xjtu.toolbox.desktop.RenderCampusExperienceKt")
    classpath = sourceSets["test"].runtimeClasspath
    args = listOf(layout.buildDirectory.dir("campus-experience-preview").get().asFile.absolutePath)
}

/** `./gradlew :desktop:packageTarGz` —— 把 jpackage 的 app-image（免安装目录）打成 `tar.gz`。 */
tasks.register<org.gradle.api.tasks.bundling.Tar>("packageTarGz") {
    group = "distribution"
    description = "把 app-image 打成 tar.gz（免安装：解压后直接跑 bin/xjtu-toolbox）"
    dependsOn("createDistributable")
    compression = org.gradle.api.tasks.bundling.Compression.GZIP
    archiveFileName.set("xjtu-toolbox-$desktopPackageVersion-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/tar"))
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
}

tasks.named<Test>("test") {
    val root = layout.buildDirectory.dir("testStoreRoot")
    doFirst { root.get().asFile.deleteRecursively() }
    systemProperty("xjtu.toolbox.keyValueRoot", root.get().asFile.absolutePath)
}
