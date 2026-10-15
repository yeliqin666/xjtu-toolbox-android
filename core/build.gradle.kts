import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// :core —— 三端共用的业务逻辑与平台抽象（KMP）。
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // 没有它 :app 消费不了 :core（jvm 变体对 Android 消费者不可见）。
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 与 :app 对齐 JVM 21：miuix-nav 的 inline 函数要求字节码目标一致。
    jvmToolchain(21)

    android {
        namespace = "com.xjtu.toolbox.core"
        compileSdk {
            version = release(37) { }
        }
        minSdk = 31
    }

    jvm()

    // 不声明 wasm 测试环境：依赖 Compose ui-graphics 后 nodejs 测试会因 skiko.mjs 报 ERR_MODULE_NOT_FOUND。
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // 拆出去的共享层与功能模块：:core 只剩壳（shell），用 api 转发给 :app / :web / :desktop / :server。
            api(project(":shared:model"))
            api(project(":shared:navigation"))
            api(project(":shared:designsystem"))
            api(project(":shared:network"))
            api(project(":feature:account"))
            api(project(":feature:agent"))
            api(project(":feature:attendance"))
            api(project(":feature:campus"))
            api(project(":feature:community"))
            api(project(":feature:game"))
            api(project(":feature:home"))
            api(project(":feature:jiaocai"))
            // api 而非 implementation：HttpClientEngine 出现在公开的 expect 声明里
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Compose Multiplatform 完整 UI 栈：android 变体转发到 androidx.compose，与 :app 的 BOM 同版不重复类。
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.animation)
            // MIUIX 的 KMP 根模块（无 -android 后缀）。
            api(libs.miuix.ui.kmp)
            api(libs.miuix.icons.kmp)
            api(libs.miuix.nav.kmp)
            api(libs.miuix.preference.kmp)
            api(libs.miuix.squircle.kmp)
            // JetBrains 版 lifecycle，与 :app 的 androidx.lifecycle 同版。
            api(libs.jb.lifecycle.viewmodel)
            api(libs.jb.lifecycle.viewmodel.compose)
            api(libs.jb.lifecycle.runtime.compose)
            api(libs.kyant.backdrop)
            api(libs.compose.material.icons.core)
            // 跨端图标：CMP 多平台版停在 1.7.3。
            api(libs.compose.material.icons.extended)
            api(libs.compose.ui.backhandler)
            // 用 api：CourseTable.termStart 是公开的 LocalDate，消费方必须能看到这个类型。
            api(libs.kotlinx.datetime)
            // 社区屏要的 Coil 与 GFM renderer（全 KMP）；`coil-network-okhttp` 留在 :app。
            api(libs.coil.compose)
            api(libs.markdown.renderer)
            api(libs.markdown.renderer.coil3)
            // markdown 解析器：社区摘要要用 AST，版本跟 renderer 走。
            api(libs.jetbrains.markdown)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.graphics.shapes)
            implementation(libs.androidx.core.ktx)
            // zxing 只有 JVM jar ⇒ 只能放在 android/jvm 的 actual 源集里。
            implementation(libs.zxing.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.zxing.core)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}

tasks.named<Test>("jvmTest") {
    val root = layout.buildDirectory.dir("testStoreRoot")
    doFirst { root.get().asFile.deleteRecursively() }
    systemProperty("xjtu.toolbox.keyValueRoot", root.get().asFile.absolutePath)
}
