// :thirdparty:backdrop —— vendor 进来的 kyant backdrop 2.0.1（Apache-2.0，来源与改动见同目录 README.md）。
//
// 为什么放进仓库而不是继续用 Maven 坐标：要给「不采样」那一档加一个开关（`DrawBackdropModifier`
// 里的 `sampling`），上游没有。包名保持 `com.kyant.backdrop` 不变，根 build.gradle.kts 用
// dependencySubstitution 把 Maven 坐标顶替成本模块，所以全部调用点一行都不用改。
//
// Compose 依赖手写、**不走 toolbox.kmp.compose**：那个约定插件自己就 `api` 了
// `kyant-backdrop` 那条 Maven 坐标，顶替之后会让本模块依赖自己（循环依赖）。
plugins {
    id("toolbox.kmp.library")
    // 上游源码里有 @Composable（Backdrop / CanvasBackdrop / CombinedBackdrop / LayerBackdrop）
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        // 上游把 jvm 与 wasmJs 共用的实现（org.jetbrains.skia 那套）放在 skikoMain 这个中间源集，
        // 而约定插件只建了 jvmMain / wasmJsMain，所以这里补一个同名源集让两者共用
        val skikoMain = create("skikoMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(skikoMain)
        wasmJsMain.get().dependsOn(skikoMain)

        commonMain.dependencies {
            api(libs.jb.compose.ui)
            api(libs.compose.ui.graphics)
            api(libs.jb.compose.foundation)
            // 只有 effects/Lens.kt 用到（圆角形状的采样范围）
            implementation(libs.kyant.shapes)
        }
    }
}
