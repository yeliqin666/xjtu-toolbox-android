import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xjtu.toolbox"
    // compileSdk 跟随 miuix（BuildConfig.COMPILE_SDK = 37）：composite build 要求消费方的
    // compileSdk 不低于依赖方，否则 CheckAarMetadata 直接失败。这只是「用哪套 SDK 编译」，
    // 不影响设备兼容范围。minSdk / targetSdk 保持不动。
    compileSdk {
        version = release(37) {
        }
    }

    defaultConfig {
        applicationId = "com.xjtu.toolbox"
        minSdk = 31
        targetSdk = 36
        versionCode = 53
        versionName = "4.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 反馈后端（飞书多维表格）的凭据。优先读环境变量（CI），否则读
        // feedback.properties（已 gitignore）。两者都没有时留空字符串——
        // FeedbackApi.isConfigured 为 false，反馈页自动降级回 GitHub/博客外链，
        // 别人 clone 下来照样能编译出可用的包。
        //
        // app_secret 终究是打进 APK 的，无法真正保密。安全性靠"这对凭据能干什么"来兜：
        // 应用只被授权写反馈表 + 读回复表，回复表里不含用户原文。详见 FeedbackApi 注释。
        run {
            val fp = rootProject.file("feedback.properties")
            val props = Properties().apply { if (fp.exists()) fp.inputStream().use { load(it) } }
            fun cfg(key: String): String =
                System.getenv("FEEDBACK_" + key.uppercase()) ?: props.getProperty(key) ?: ""
            buildConfigField("String", "FEEDBACK_APP_ID", "\"${cfg("appId")}\"")
            buildConfigField("String", "FEEDBACK_APP_SECRET", "\"${cfg("appSecret")}\"")
            buildConfigField("String", "FEEDBACK_BASE_TOKEN", "\"${cfg("baseToken")}\"")
            buildConfigField("String", "FEEDBACK_TABLE_SUBMIT", "\"${cfg("tableSubmit")}\"")
            buildConfigField("String", "FEEDBACK_TABLE_REPLY", "\"${cfg("tableReply")}\"")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_PATH")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // 本地开发：release.jks + keystore.properties（两者都已 gitignore）。
                // 口令不写在源码里——这个文件是要提交的，硬编码等于把签名密钥口令公开。
                // 缺任一文件则不配置签名，release 构建产出未签名包。
                val localKeystore = rootProject.file("release.jks")
                val props = rootProject.file("keystore.properties")
                if (localKeystore.exists() && props.exists()) {
                    // 顶部 import java.util.Properties：Kotlin DSL 里裸 `java.` 会被
                    // Gradle 的 java 扩展遮蔽，只能靠 import 引入
                    val p = Properties()
                    props.inputStream().use { p.load(it) }
                    storeFile = localKeystore
                    storePassword = p.getProperty("storePassword")
                    keyAlias = p.getProperty("keyAlias")
                    keyPassword = p.getProperty("keyPassword")
                }
            }
            // minSdk=31 全覆盖 V3 支持范围（API≥28），强制启用以获得更强签名保护和密钥轮换能力
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            // 用 release 的签名给 debug 包签名。
            // 目的：签名一致才能直接覆盖安装设备上已有的 release 版，不必先卸载——
            // 卸载会连登录态和缓存一起清掉，排查问题时每次都要重登，很折腾。
            // debug 不开 minify，proguard 里那条 -assumenosideeffects 也就不生效，
            // Log 会完整保留，这正是抓日志需要的。
            // 没配 release 签名时（例如 CI 上没有 keystore）保持默认 debug 签名。
            signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
                ?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material.icons)
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.urlconnection)
    implementation(libs.jsoup)
    implementation(libs.flexmark.html2md)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.navigation.compose)
    implementation(libs.security.crypto)
    implementation(libs.zxing.core)
    // 扫码登录：CameraX 取景 + zxing 解码
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // 底栏中心屁岱按钮的形象动画。素材是 Google Noto Animated Emoji（OFL-1.1 / Apache-2.0），
    // 官方一整条 164 帧动画，靠 LottieClipSpec 切段复用出待命/提醒/点击三个状态，
    // 不需要额外素材，也不需要改 JSON。见 agent/PidaiNavButton.kt。
    implementation(libs.lottie.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
    // 版本号仅为占位：settings.gradle.kts 的 dependencySubstitution 会把这三个坐标
    // 替换成 includeBuild("miuix-ref") 里的本地工程，实际编译的永远是源码树当前状态。
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
