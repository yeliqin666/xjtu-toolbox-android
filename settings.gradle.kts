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
    }
}

rootProject.name = "XJTUToolBox"
include(":app")
// 只在本地/CI 手动跑 generateBaselineProfile 时用到；日常 assemble 不会构建它的测试代码
include(":baselineprofile")
// 三端共用的业务与平台抽象（KMP）。当前是传输层探针阶段：只有 jvm / wasmJs 目标，
// 用来验证把 okhttp 换成 Ktor 之后非 Android 端能否真跑通。
include(":core")
