// :shared:data —— 取数与会话（okhttp + jsoup，含 WebVPN 与各站登录态），只有 JVM / Android 变体，不含 Compose。
plugins {
    // ⚠️ 只能有 jvm + android 两个目标：OkHttp / jsoup 没有 wasm 变体。
    id("toolbox.kmp.jvm-android")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
            api(project(":shared:navigation"))
            api(project(":shared:network"))
            // api：okhttp 与 jsoup 的类型出现在公开签名里。
            api(libs.okhttp)
            api(libs.jsoup)
            // BrotliInterceptor（校园网某些接口只发 br）。
            implementation(libs.okhttp.brotli)
            implementation(libs.okhttp.java.net.cookiejar)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
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
