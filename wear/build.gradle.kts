import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xjtu.toolbox.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xjtu.toolbox.wear"
        // Wear OS 最低 API 26（Oreo），新款手表均 ≥ API 30 但保留向下兼容
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-wear"
    }

    // 与 :app 用同一套密钥：手表 APK 与手机 APK 属于同一发布主体，
    // 签名一致才能共用 Data Layer 通信与后续的手表端配套安装校验。
    // 取值优先级与 :app 完全一致：CI 环境变量 > 本地 release.jks + keystore.properties。
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_PATH")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // 口令不写在源码里——这个文件要提交，硬编码等于把签名密钥口令公开。
                // 缺任一文件则不配置签名，release 构建产出未签名包（与 :app 行为一致）。
                val localKeystore = rootProject.file("release.jks")
                val props = rootProject.file("keystore.properties")
                if (localKeystore.exists() && props.exists()) {
                    val p = Properties()
                    props.inputStream().use { p.load(it) }
                    storeFile = localKeystore
                    storePassword = p.getProperty("storePassword")
                    keyAlias = p.getProperty("keyAlias")
                    keyPassword = p.getProperty("keyPassword")
                }
            }
            // minSdk=26 时 V3 需 API≥28，低版本手表由 V2 兜底，开启不影响兼容性
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.coroutines.android)
    // Wear OS 官方 home tiles / complications API
    implementation("androidx.wear:wear:1.3.0")
    debugImplementation(libs.androidx.ui.tooling)
}