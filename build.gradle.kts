// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // 版本必须在根工程钉死：miuix 的 composite build 也往 classpath 上放了一份 AGP，
    // 子工程再带版本请求会撞上"已在 classpath 且版本未知"而解析失败。
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    // KMP / Compose / JVM 插件同样必须在根工程钉死版本，子工程只用 bare id。
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// vendor 进来的 backdrop 顶替 Maven 上的 io.github.kyant0:backdrop：`toolbox.kmp.compose`
// 仍然 `api` 那条坐标，这里统一换成 :thirdparty:backdrop，于是现有十来处 `drawBackdrop`
// 调用点一行都不用改。顶替只在这一处声明，别在子工程里再写一遍。
allprojects {
    // 本模块自己就是实现，不能再被顶替成「依赖自己」
    if (path == ":thirdparty:backdrop") return@allprojects
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.kyant0:backdrop"))
                .using(project(":thirdparty:backdrop"))
                .because("本地 vendor：为「不采样」档加 sampling 开关，视觉代码与上游 2.0.1 一致")
        }
    }
}