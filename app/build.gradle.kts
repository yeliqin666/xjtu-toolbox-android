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
        versionCode = 47
        versionName = "4.61"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.navigation.compose)
    implementation(libs.security.crypto)
    implementation(libs.zxing.core)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
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
