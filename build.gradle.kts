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
    // :core 的 KMP 插件同属上面那份 KGP jar，同理必须在根工程钉死，
    // 子工程只 bare id 引用；带版本请求会报「已在 classpath 且版本未知」。
    alias(libs.plugins.kotlin.multiplatform) apply false
    // :desktop 是纯 JVM 的 Compose Desktop 应用；版本同样在根工程钉死。
    alias(libs.plugins.kotlin.jvm) apply false
    // :web 是 Compose Multiplatform 的 wasmJs 应用；插件同样在根工程钉死版本。
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}