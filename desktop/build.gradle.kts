// :desktop —— 桌面端（Linux / Windows）的**窗口模式**：Compose Desktop 原生窗口。
//
// 为什么是纯 JVM 模块而不是 KMP：这一端不需要任何 wasm/ios 目标，而 Compose Desktop 的
// `application` / jpackage 任务在 kotlin("jvm") 上是官方模板形态（少一层「单 jvm 目标的 KMP
// 里 compose.desktop.application 认哪个目标」的坑）。`:core` 是 KMP，但它的 jvm 变体对
// 普通 JVM 消费者是可见的（Gradle Module Metadata 按属性选变体），所以 `project(":core")` 直接用。
//
// 这一端**没有自己的业务代码**：屏、ViewModel、模型、取数端口全在 `:core`；数据源是 `:core` 里
// 那 13 个 campus-api 版实现（`Campus*Api`），只把基址指向本机的 127.0.0.1:3099。
// ⚠️ 那是**脚手架**：它只对「我这台跑着 campus-api 的机器」可用，正是因此它只出现在阶段 0。
// Stage A 会把数据源换成 `:data`（自带登录、自带数据，见 docs/desktop-port-plan.md §6）。
/**
 * 桌面包的版本号。单独拎出来是因为它出现在两个地方：jpackage 的 `packageVersion`
 * （只接受 `x.y.z`）与 tar.gz 的文件名。
 */
val desktopPackageVersion = "5.1.1"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // 与 :app / :core 对齐：miuix 各模块都是 JDK 21 工具链编译的，字节码目标不一致会直接编译失败。
    jvmToolchain(21)
}

dependencies {
    // 唯一一份 UI 与业务逻辑
    implementation(project(":core"))
    // Compose Desktop 的当前平台运行时（含 skiko 与 Swing 调度器）
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // 读 campus-api 那份裸 JSON（`:core` 只在内部用它解析，没往上暴露类型）
    implementation(libs.kotlinx.serialization.json)
    // `Dispatchers.Main` 在桌面 = AWT 事件队列。:core 的屏一律用 `viewModel { }` + `viewModelScope`，
    // 而 viewModelScope 跑在 Main.immediate 上 —— 少了这个，离屏渲染与真窗口都会以
    // 「Dispatchers.Main[missing]」失败。Compose Desktop 自己不转递它。
    implementation(libs.kotlinx.coroutines.swing)
    // 脚手架数据源走的是 `:core` 的 Ktor 客户端（`createToolboxClient()` → `toolboxEngine()`），
    // jvm 那份引擎是 okhttp（`core/src/jvmMain/.../ToolboxEngine.jvm.kt`），所以要把引擎显式拉进来。
    implementation(libs.ktor.client.okhttp)
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
        // jpackage：`./gradlew :desktop:packageDeb` / `:desktop:packageTarGz` / `createDistributable`。
        // 出包形态按 `docs/desktop-port-plan.md` §7 定下来的三样：`.deb` / app-image（免安装目录）
        // / 由 app-image 再打的 `tar.gz`。
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage)
            packageName = "xjtu-toolbox"
            // jpackage 的版本号规则比 :app 的 versionName 严：只接受 x.y.z（或带 -ea 之类后缀）
            packageVersion = desktopPackageVersion
            description = "西安交通大学工具箱（桌面预览）"
            vendor = "XJTU ToolBox"

            linux {
                menuGroup = "Education"
                appCategory = "Education"
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
 */
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "把 :core 的屏离屏渲染成 PNG（无需显示服务器）"
    mainClass.set("com.xjtu.toolbox.desktop.RenderScreensKt")
    classpath = sourceSets["main"].runtimeClasspath
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
