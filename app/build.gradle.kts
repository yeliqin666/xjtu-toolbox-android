plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xjtu.toolbox"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.xjtu.toolbox"
        minSdk = 31
        targetSdk = 36
        versionCode = 11
        versionName = "2.7.1"

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
                // 本地开发：从项目根目录读取 release.jks
                val localKeystore = rootProject.file("release.jks")
                if (localKeystore.exists()) {
                    storeFile = localKeystore
                    storePassword = "XjtuToolbox2026!"
                    keyAlias = "xjtu-toolbox"
                    keyPassword = "XjtuToolbox2026!"
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
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.5")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.8.5")
    implementation("dev.chrisbanes.haze:haze-android:1.7.2")
    implementation("dev.chrisbanes.haze:haze-materials-android:1.7.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}