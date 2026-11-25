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
            implementation(libs.latex.renderer)
            implementation(libs.latex.fonts.katex)
            // GithubMarkdown：GFM 渲染与 Coil 取图（全 KMP）；网络取图组件由各端自己加（Android 在 :app）
            implementation(libs.coil.compose)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.coil3)
            // markdownSummary 要用 AST，版本跟 renderer 走
            implementation(libs.jetbrains.markdown)
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
