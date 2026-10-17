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