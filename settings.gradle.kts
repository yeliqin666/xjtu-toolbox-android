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
    // 不用 FAIL_ON_PROJECT_REPOS：KGP 会以工程级仓库加 Node/Yarn 发行包且无法关掉；PREFER_SETTINGS 下工程级仓库被忽略。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Node / Yarn 发行包，includeModule 锁死到这两个构件；Yarn 在 GitHub Releases，校园网要走代理。
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
        // binaryen（wasm 发布产物要用）：KGP 以工程级仓库加，PREFER_SETTINGS 下会被忽略，所以在这里声明；缺了报 `Could not find com.github.webassembly:binaryen`。
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
include(":core")
include(":web")
include(":data")

// 假校园上游：只给测试与离屏证据用，不进任何交付物。
include(":testkit")

include(":desktop")

include(":server")
