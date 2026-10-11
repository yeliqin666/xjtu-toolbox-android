pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // 为什么不是 FAIL_ON_PROJECT_REPOS：Kotlin/Wasm 的工具链要下载 Node 与 Yarn 的发行包
    // （两者都不在 Maven 上），KGP 会把 nodejs.org/dist 与 github.com/yarnpkg/yarn 作为
    // **工程级**仓库塞进来，FAIL_ON_PROJECT_REPOS 会直接把整个构建闸掉。
    // 实测：在 settings 层声明这两个仓库**并不能**阻止插件去加（它照样加、照样失败），
    // 而 KGP 2.4.20 也没有受支持的「不要下载」入口（NodeJsRootExtension.download 是 ERROR
    // 级废弃、YarnRootExtension.download 已不存在，EnvSpec 实例在构建脚本里拿不到）。
    // 所以只能放宽到 PREFER_SETTINGS。
    //
    // 注意安全性质没有变：PREFER_SETTINGS 下工程级仓库会被**忽略**，解析源仍然只来自
    // settings（下面这些）。失去的只是「有人往工程里塞仓库就响亮失败」这层告警。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Node / Yarn 的发行包。includeModule 把它锁死到两个构件，不可能影响其他依赖的解析。
        // Yarn 的包在 GitHub Releases，本机校园网直连不通，走 ~/.gradle/gradle.properties 里的代理。
        ivy("https://nodejs.org/dist") {
            name = "Node Distributions at https://nodejs.org/dist"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn Distributions at https://github.com/yarnpkg/yarn/releases/download"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        // binaryen：Kotlin/Wasm 的 `binaries.executable()`（wasmJsBrowserDistribution）要它把
        // wasm 优化/链接成可发布产物。同样是 GitHub Releases，同样必须在这里声明 ——
        // KGP 会把它当成**工程级**仓库加，而工程级仓库在 PREFER_SETTINGS 下会被忽略。
        // 坐标与 pattern 逐字对齐 KGP 的 BinaryenSetupTask（artifactPattern=version_[revision]/…）。
        // 缺失时的报错是 `Could not find com.github.webassembly:binaryen:<ver>`，很像依赖写错，
        // 实际是仓库没露出来。
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen Distributions at https://github.com/WebAssembly/binaryen/releases/download"
            patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "XJTUToolBox"
include(":app")
// 只在本地/CI 手动跑 generateBaselineProfile 时用到；日常 assemble 不会构建它的测试代码
include(":baselineprofile")
// 三端共用的业务与平台抽象（KMP）。当前是传输层探针阶段：只有 jvm / wasmJs 目标，
// 用来验证把 okhttp 换成 Ktor 之后非 Android 端能否真跑通。
include(":core")
// Web 端（Kotlin/Wasm + Compose Multiplatform + MIUIX）—— 第一个**吃 :core** 的真实端。
// 它不是新的一套业务代码：数据模型、Ktor 客户端、会话存储全来自 :core，
// 这里只放「浏览器外壳 + 屏幕」，用来证明共享层在非 Android 端真的能跑真数据。
include(":web")
include(":data")

// 假校园上游（图书馆座位系统 + 统一认证）—— **只给测试与离屏证据用**，不在任何交付物里。
// 为什么独立成模块、以及为什么页面原文只能有一份：见 testkit/build.gradle.kts 的 KDoc。
include(":testkit")

// 桌面端（Linux / Windows）的**窗口模式**：Compose Desktop 原生窗口，进程内直取数据。
// 这是 docs/desktop-port-plan.md 的阶段 0.1 —— 目的只有一个：证明 :core 的屏在这台 Linux 上
// 真能渲染、MIUIX 的桌面观感/鼠标/输入法可接受、jpackage 能出包。
include(":desktop")

// serve 模式（同一份设计的另一半，D3）：**无窗口的常驻进程** —— Ktor 托管 `:web` 的 wasm 产物
// 并实现 `/api/*`（浏览器访问）。它既不是「端」也不是数据层：UI 是 `:web` 的，数据是 `:data` 的，
// 这里只有 HTTP 外壳（默认只监听 127.0.0.1 + 访问令牌，见 docs/api-contract.md §3）。
include(":server")
