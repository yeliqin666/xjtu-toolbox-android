// :feature:settings —— 设置、协议、档案导入导出。只依赖 :shared:*，不依赖别的 feature。
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
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
