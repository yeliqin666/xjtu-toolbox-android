// :shared:designsystem —— MIUIX 主题、玻璃效果、通用组件与 Compose 侧的平台缝。
plugins {
    id("toolbox.kmp.library")
    id("toolbox.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.androidx.graphics.shapes)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            // COUI 底栏（ui.glass.coui）的弹簧与附件收放动画
            implementation(libs.androidx.dynamicanimation)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
