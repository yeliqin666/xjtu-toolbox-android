// :feature:agent —— 屁岱（Agent）对话、富控件与形象。只依赖 :shared:*，不依赖别的 feature。
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
            implementation(libs.latex.renderer)
            implementation(libs.latex.fonts.katex)
        }
    }
}
