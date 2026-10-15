// :shared:designsystem —— MIUIX 主题、玻璃效果、通用组件与 Compose 侧的平台缝。
plugins {
    id("toolbox.kmp.library")
    id("toolbox.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.graphics.shapes)
            implementation(libs.androidx.core.ktx)
        }
    }
}
