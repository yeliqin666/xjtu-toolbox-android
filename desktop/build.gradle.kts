import java.io.File
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// :desktop —— 桌面端（Linux / Windows）的**窗口模式**：Compose Desktop 原生窗口。
//
// 为什么是纯 JVM 模块而不是 KMP：这一端不需要任何 wasm/ios 目标，而 Compose Desktop 的
// `application` / jpackage 任务在 kotlin("jvm") 上是官方模板形态（少一层「单 jvm 目标的 KMP
// 里 compose.desktop.application 认哪个目标」的坑）。`:core` 是 KMP，但它的 jvm 变体对
// 普通 JVM 消费者是可见的（Gradle Module Metadata 按属性选变体），所以 `project(":core")` 直接用。
//
// ## 这一端有什么（Stage A 第二步之后）
//
// - `:core` —— 屏 / ViewModel / 模型 / 端口（三端唯一一份）；
// - `:data` —— **数据层与会话内核**：它自己发 CAS 登录、自己维持会话、自己取数。
//   桌面端**不依赖 campus-api**（那是设计文档 C2：任何人独立安装、独立登录）。
//
// 所以 `src/main` 里只剩四样东西：窗壳（`Main`）、桌面外壳（`ToolboxDesktopApp`）、
// 登录装配（`DesktopAuth`）、图书馆端口到 `:data` 的适配（`DesktopLibrarySource`）。
// 业务一行都没有。
//
// ⚠️ **脚手架已经拆掉了**：Stage 0 时这里曾经把 `:core` 里那 13 个 `Campus*Api` 指向本机
// `127.0.0.1:3099` 的 campus-api，好让 19 条路由都能画出画面。那违反 C2（它只对「我这台跑着
// campus-api 的机器」可用），所以这一轮换成了 `:data` + 窗口里自己登录：**校历 / 图书馆 / 黄页三条路由**
// 是真数据（校历与黄页是免登录的公开门户接口；图书馆要登录、走会话内核），其余路由如实显示「还没搬到桌面」
//（`NotPortedScreen`）—— 它们卡的是各自的 `*Api` 还在 `:app`，
// 见 `docs/desktop-port-plan.md` §3.1 与 §5.1。
//
// ## 离屏渲染证据为什么在 `src/test` 里
//
// `renderScreens` 要画出「用真会话登进去的图书馆」，就得有一个假的上游（真站点进不去、也不该进）。
// 那个假上游是 `:testkit` —— **一个绝不能被任何交付物依赖到的模块**。所以证据生成器
// （`RenderScreens`）与它一起待在 test 源集：`createDistributable` / `packageDeb` / `tar.gz`
// 只会打包 `src/main` 的编译产物，假上游连影子都不该有。
/**
 * 桌面包的版本号。单独拎出来是因为它出现在两个地方：jpackage 的 `packageVersion`
 * （只接受 `x.y.z`）与 tar.gz 的文件名。
 */
val desktopPackageVersion = "5.1.1"

/**
 * 反馈后端的凭据（与 `:app` **同一份来源**）：优先环境变量（CI = GitHub Actions secrets），
 * 否则读仓库根目录的 `feedback.properties`（已 gitignore）。两者都没有时留空串 ⇒
 * `FeedbackApi.isConfigured` 为 false，反馈页降级成只剩 GitHub 外链（与 Android 同一条口径）。
 *
 * 为什么在构建期读、而不是运行期读：桌面端是**可分发**的（`.deb` / tar.gz），装到别人机器上时
 * 仓库根目录那份文件早就不在了。构建期烘焙进去与 Android 的做法逐字同源（见 `app/build.gradle.kts`
 * 里那段 `cfg()`）—— 也正因为 `app_secret` 终究是烘焙进产物的，两端的权限都只给到
 * 「写反馈表 / 读回复表」（见 `:data` 的 `FeedbackApi` KDoc 里的安全模型）。
 */
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

/**
 * 把上面那份凭据生成成一个 Kotlin 常量对象（`DesktopBuildConfig`）—— 纯 JVM 模块没有
 * Android 那边的 `buildConfigField`，所以自己写一个生成任务。
 *
 * 生成目录在 `build/` 下（gitignored）：凭据不进仓库，也不会被误提交。
 */
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
    // 与 :app / :core 对齐：miuix 各模块都是 JDK 21 工具链编译的，字节码目标不一致会直接编译失败。
    jvmToolchain(21)
    // 生成的构建常量（上面那个任务）：`srcDir(taskProvider)` 会让 `compileKotlin` 自动依赖它。
    sourceSets["main"].kotlin.srcDir(generateDesktopBuildConfig)
}

dependencies {
    // 唯一一份 UI 与业务逻辑
    implementation(project(":core"))
    // 数据层与会话内核：桌面端自己登录、自己取数（不经过任何我方的服务器）
    implementation(project(":data"))
    // Compose Desktop 的当前平台运行时（含 skiko 与 Swing 调度器）
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // `Dispatchers.Main` 在桌面 = AWT 事件队列。:core 的屏一律用 `viewModel { }` + `viewModelScope`，
    // 而 viewModelScope 跑在 Main.immediate 上 —— 少了这个，离屏渲染与真窗口都会以
    // 「Dispatchers.Main[missing]」失败。Compose Desktop 自己不转递它。
    implementation(libs.kotlinx.coroutines.swing)
    // 教材全文库的书架在本端是**一份 JSON 落盘**（`:core` 的 `Jiaocai1ShelfStore` 的桌面实现，
    // 见 `jiaocai1/DesktopJiaocai1Storage.kt`）：解析用 `:core` 的 `AppJson`，那个类型来自
    // kotlinx.serialization，而 `:core` / `:data` 都只把它声明成 implementation ⇒ 这一端自己显式要一份。
    implementation(libs.kotlinx.serialization.json)

    // ── test：只给离屏渲染证据与桌面端装配的验收测试用 ────────────────────────
    // 假校园上游（图书馆座位系统 + 统一认证）。它**不进**任何交付物：见文件头与
    // testkit/build.gradle.kts 的 KDoc。
    testImplementation(project(":testkit"))
    testImplementation(kotlin("test"))
    // JUnit4：`kotlin("test")` 在 kotlin-jvm 上默认落到 kotlin-test-junit，引擎要显式给。
    testImplementation(libs.junit)
    // 黄页那条免登录上游是**Ktor 接口**（`:core` 的 `YellowPageApi` 收一个 HttpClient）⇒ 用 Ktor 自己的
    // `MockEngine` 把假响应喂进去，比再起一个 HTTP 服务器干净（图书馆/校历那两条要服务器，
    // 是因为它们面对的是会话内核的 cookie/302）。
    testImplementation(libs.ktor.client.mock)
    // 付款码那条路由的「真出码」判据：把 `:core` 编出来的模块阵列拿 zxing 的**低层解码器**
    // 解回同一串数字（掩码 / RS 纠错 / 格式信息 / 数据排布全得对）。`:core` 的 jvmMain 自己
    // 已经有 zxing（implementation，不进编译类路径）⇒ 测试这一侧显式要一份。
    testImplementation(libs.zxing.core)
}

/**
 * `./gradlew :desktop:run` —— 真窗口。
 *
 * 没有 DISPLAY 的机器（CI、纯 ssh）跑不了窗口，但**屏的渲染**仍然可以被验证：
 * 见 `renderScreens` 任务（离屏渲染成 PNG，走同一套 skiko 渲染栈）。
 */
compose.desktop {
    application {
        mainClass = "com.xjtu.toolbox.desktop.MainKt"
        // jpackage：`./gradlew :desktop:packageDeb` / `:desktop:packageTarGz` / `createDistributable`
        //（Linux，本机就能验）与 `:desktop:packageMsi` / `:desktop:packageExe`（Windows）。
        //
        // ⚠️ **Windows 的包只能在 Windows 上打**：jpackage 不接受交叉构建，它要调 Windows 自己的
        // WiX（`candle.exe`/`light.exe`）—— Compose Desktop 会自己下载一份 WiX 3.11 到 gradle 用户目录
        //（`compose.desktop.application.downloadWix`，也能用 `WIX_PATH` 指一份现成的）。
        // 所以 Windows 那两个 target 在这里只是**声明**：任务会注册，但在 Linux 上执行会失败；
        // 真出包的路在 `.github/workflows/desktop-windows.yml`（windows-latest）。
        // 出包形态按 `docs/desktop-port-plan.md` §7 定下来的三样（`.deb` / app-image / `tar.gz`）
        // + `docs/windows-delivery.md` 定下来的两样（`.msi` / `.exe`）。
        nativeDistributions {
            targetFormats(
                // Linux（§7.1 三样）：`packageDeb` / `createDistributable` / `packageTarGz`
                TargetFormat.Deb,
                TargetFormat.AppImage,
                // Windows（docs/windows-delivery.md）：.msi 是企业分发/组策略常用的那一种，
                // .exe 是普通用户双击就走的那种（两者都需要 Windows + WiX）
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "xjtu-toolbox"
            // jpackage 的版本号规则比 :app 的 versionName 严：只接受 x.y.z（或带 -ea 之类后缀）
            packageVersion = desktopPackageVersion
            description = "西安交通大学工具箱（桌面预览）"
            vendor = "XJTU ToolBox"
            copyright = "Copyright © XJTU ToolBox contributors"

            linux {
                // 桌面项的图标（`.desktop` 的 `Icon=`）：**应用自己的图标**，不是 Compose Desktop 那套
                // 默认图标。源是仓库里那份自适应图标的两片矢量 —— `app/src/main/res/drawable/`
                // 的 `ic_launcher_background.xml`（#87CEEB 满幅）与 `ic_launcher_foreground.xml`
                //（远山 / 主峰 / 雪顶 / 地面线）：转写成 108×108 viewport 的 SVG 后光栅化成 256×256 的
                // PNG（生成命令与实测见 docs/linux-delivery.md §2.1）。
                // ⚠️ **不要**拿 `mipmap-*/ic_launcher.webp` 当源 —— 那是 Android Studio 模板的绿机器人
                // 占位图，不是应用的图标（Windows 那份 `icons/windows.ico` 恰好就是拿它转的，见
                // docs/windows-delivery.md §7 第 10 条）。
                iconFile.set(project.file("icons/linux.png"))
                menuGroup = "Education"
                appCategory = "Education"
                // `.deb` 的 `Maintainer:`。⚠️ 实测（本机真出包 + `dpkg-deb -I`）：jpackage 的
                // `--linux-deb-maintainer` 只收**邮箱**，控制文件那一行是它自己拼的 `<vendor> <这一串>`。
                // 第一次传了完整的 `名字 <邮箱>` ⇒ 出来的是 `XJTU ToolBox <XJTU ToolBox <邮箱>>`（双层
                // 尖括号），所以这里只给地址。不设的话它填 `<vendor> <Unknown>`。
                // 用的身份是仓库里能核到的：`git log` 里桌面交付那两批（b624008 / 603ece98）以及最近
                // 200 次提交里 133 次的作者，且是 GitHub 的 noreply 地址（不发个人邮箱）。换人改这一行。
                debMaintainer = "114236693+Triangle-GitHub@users.noreply.github.com"
            }

            /**
             * Windows 那一套（都是 jpackage 的 `--win-*` / WiX 参数），逐项写清为什么：
             *
             *  - [WindowsPlatformSettings.perUserInstall] `true`：装到用户自己的目录（`%LOCALAPPDATA%`）、
             *    **不弹 UAC**。这一端本来就是「每个人装自己一份、装完各登各的」——凭据用 DPAPI 的
             *    **当前用户**作用域（见 `:data` 的 `DpapiSecretProtector`），装上就能用；
             *  - [WindowsPlatformSettings.upgradeUuid]：**升级与卸载靠它认人**（MSI 的 UpgradeCode）。
             *    发过一次版就**绝对不能改** —— 改了就是「另一个产品」，老版本不会被升级/卸载，
             *    而是并存两个；
             *  - [WindowsPlatformSettings.menuGroup]：开始菜单里的文件夹名（与 Linux 那栏同一个词）；
             *  - [AbstractPlatformSettings.iconFile]：`desktop/icons/windows.ico`（多尺寸，从 Android 的
             *    `mipmap-*dpi/ic_launcher.webp` 转出来的；jpackage 在 Windows 上只收 `.ico`）。
             *    不用 app-image 里那套默认的 Java 图标 —— 装出来在开始菜单/控制面板里就是「Java」。
             *    ⚠️ 如实记一笔：那份 `.webp` 是 Android Studio 模板的**绿机器人占位图**，不是应用的图标
             *    （应用自己的图标是 Linux 那份 `linux.png` 用的自适应矢量）。换掉它在本机（Linux）验不了：
             *    jpackage 不能交叉出包，只能等 Windows 上真打一次 —— 见 docs/windows-delivery.md §7 第 10 条。
             *
             * 其余几个用默认值：`console = false`（不开控制台窗）、`shortcut = true` / `menu = true`
             *（桌面快捷方式 + 开始菜单）、`dirChooser = true`（安装时可选目录）、`msiPackageVersion` /
             * `exePackageVersion` 默认取 `packageVersion`。
             */
            windows {
                perUserInstall = true
                upgradeUuid = "528a294e-23ee-4307-a37a-1a71bb591fa0"
                menuGroup = "Education"
                iconFile.set(project.file("icons/windows.ico"))
            }
        }
    }
}

/**
 * `./gradlew :desktop:renderScreens` —— **离屏**把 :core 的屏渲染成 PNG。
 *
 * 为什么需要它：这台机器没有显示服务器（`DISPLAY` 为空），`run` 起不来窗口，
 * 但「屏在桌面真渲染」这条验收不能靠肉眼之外的东西。`ImageComposeScene` 走的是同一个
 * skiko/Skia 渲染栈（同一套 MIUIX 组件、同一份布局代码），只是把结果画到一张位图上而不是屏幕上。
 *
 * ⚠️ 它的类路径是 **test** 源集：证据生成器与假上游（`:testkit`）都在那里 ——
 * 假统一认证绝不能出现在交付物里（见文件头）。
 */
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "把 :core 的屏离屏渲染成 PNG（无需显示服务器；含真登录后的图书馆）"
    mainClass.set("com.xjtu.toolbox.desktop.RenderScreensKt")
    classpath = sourceSets["test"].runtimeClasspath
    args = listOf(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

/**
 * `./gradlew :desktop:packageTarGz` —— 把 jpackage 的 **app-image**（免安装目录）打成 `tar.gz`。
 *
 * 为什么要有它：`docs/desktop-port-plan.md` §7 定下来的 Linux 交付三样里，`tar.gz` 是
 * 给「没有 dpkg / 不能用 FUSE」的机器用的 —— 解压后直接跑 `bin/xjtu-toolbox`，
 * 不需要安装、也不需要 root。
 *
 * ⚠️ 口径说明：Compose Desktop 的 `TargetFormat.AppImage` 是 **jpackage 的 `app-image`**
 * （一个自带运行时的目录），**不是** AppImage.org 那种单文件 `.AppImage`。真正的单文件
 * AppImage 需要额外一步 `appimagetool`（且它依赖 FUSE），不在这条任务里 —— 见 §7。
 */
tasks.register<org.gradle.api.tasks.bundling.Tar>("packageTarGz") {
    group = "distribution"
    description = "把 app-image 打成 tar.gz（免安装：解压后直接跑 bin/xjtu-toolbox）"
    dependsOn("createDistributable")
    compression = org.gradle.api.tasks.bundling.Compression.GZIP
    archiveFileName.set("xjtu-toolbox-$desktopPackageVersion-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/tar"))
    // app-image 的目录名就是 packageName，里面 bin/ + lib/ + runtime/ 整棵带走
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
}
