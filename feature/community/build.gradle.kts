// :feature:community —— 社区（GitHub Discussions）、搭子匹配、反馈。只依赖 :shared:*，不依赖别的 feature。
plugins {
    id("toolbox.kmp.library")
    id("toolbox.kmp.compose")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            api(project(":shared:model"))
            api(project(":shared:navigation"))
            api(project(":shared:designsystem"))
            api(project(":shared:network"))
            // 头像用 Coil（全 KMP）；`coil-network-okhttp` 留在 :app。正文渲染在 :shared:designsystem 的 GithubMarkdown。
            api(libs.coil.compose)
        }
    }
}
