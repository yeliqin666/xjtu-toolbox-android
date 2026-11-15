// :feature:lms —— 思源学堂与下载 / 播放。只依赖 :shared:*，不依赖别的 feature。
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
        }
        commonTest.dependencies {
            // 下载那一族的 serve 支（`ServeDownloadsApi`，契约 §5.13）要喂假响应
            implementation(libs.ktor.client.mock)
        }
    }
}
