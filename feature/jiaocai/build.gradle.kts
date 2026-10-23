// :feature:jiaocai —— 教材（含全文阅读）。只依赖 :shared:*，不依赖别的 feature。
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
        jvmTest.dependencies {
            // 教材全文库那一族的 serve 支（`ServeJiaocai1Api`，契约 §5.14）要喂假响应；
            // ⚠️ 放 jvmTest 而不是 commonTest：mock 引擎在 wasmJs 上没必要惊动
            implementation(libs.ktor.client.mock)
        }
    }
}
