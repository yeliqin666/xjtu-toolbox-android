// :data —— 取数与会话（okhttp + jsoup，只有 JVM / Android 变体，所以不能进 :core）。
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // 不用 `org.json`：JVM 单测里它是 android.jar 的桩，一调就抛。
    alias(libs.plugins.kotlin.serialization)
    // 场馆滑块验证码是 @Composable，需要 Compose；⚠️ `:data` 只能有 jvm + android 两个目标。
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.xjtu.toolbox.data"
        compileSdk {
            version = release(37) { }
        }
        minSdk = 31
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            // api：okhttp 与 jsoup 的类型出现在公开签名里。
            api(libs.okhttp)
            api(libs.jsoup)
            // 取数与会话已拆到 :shared:data；这里只剩场馆滑块验证码（@Composable + 识别器），api 转发给各端壳。
            api(project(":shared:data"))
            // api：`SessionExpiredFailure` 在 :core，消费方要看得见这个父类型。
            api(project(":core"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // BrotliInterceptor（校园网某些接口只发 br）。
            implementation(libs.okhttp.brotli)
            implementation(libs.okhttp.java.net.cookiejar)
            // ⚠️ OkHttp 引擎只有 JVM/Android；给 `:data` 加 wasm/ios 目标这里就编不过。
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.miuix.ui.kmp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }

        jvmTest.dependencies {
            // 假上游在 `:testkit`（桌面端离屏渲染也要用，test 源集跨模块共享不了）。
            implementation(project(":testkit"))
        }
        androidMain.dependencies {
            implementation(libs.security.crypto)
        }
    }
}

tasks.named<Test>("jvmTest") {
    val root = layout.buildDirectory.dir("testStoreRoot")
    doFirst { root.get().asFile.deleteRecursively() }
    systemProperty("xjtu.toolbox.keyValueRoot", root.get().asFile.absolutePath)
}
