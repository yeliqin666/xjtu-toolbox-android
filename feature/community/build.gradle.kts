// :feature:community —— 社区（GitHub Discussions）、搭子匹配、反馈。只依赖 :shared:*，不依赖别的 feature。
plugins {
    id("toolbox.kmp.library")
    id("toolbox.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
            api(project(":shared:navigation"))
            api(project(":shared:designsystem"))
            api(project(":shared:network"))
            // 社区屏要的 Coil 与 GFM renderer（全 KMP）；`coil-network-okhttp` 留在 :app。
            api(libs.coil.compose)
            api(libs.markdown.renderer)
            api(libs.markdown.renderer.coil3)
            // markdown 解析器：社区摘要要用 AST，版本跟 renderer 走。
            api(libs.jetbrains.markdown)
        }
    }
}
