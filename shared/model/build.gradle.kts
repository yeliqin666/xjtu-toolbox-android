// :shared:model —— 纯 Kotlin：数据类、领域接口（各域 *Source）、Agent 工具接口、不含 Compose 的平台缝（expect/actual）。
plugins {
    id("toolbox.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ⚠️ 唯一例外：`InboxStore.version` 是 Compose 快照状态（界面靠它重组），只要 runtime，不开 Compose 编译器插件。
            implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.composeMultiplatform.get()}")
        }
        // zxing 只有 JVM jar ⇒ 只能放在 android/jvm 的 actual 源集里。
        androidMain.dependencies {
            implementation(libs.zxing.core)
        }
        jvmMain.dependencies {
            implementation(libs.zxing.core)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
