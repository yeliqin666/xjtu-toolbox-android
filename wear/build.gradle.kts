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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Wear OS 官方 home tiles/complications API
    implementation("androidx.wear:wear:1.3.0")
    // 暂不引入 androidx.wear.compose:compose 以减小依赖；后续 PR 加 Watch Face
    debugImplementation(libs.androidx.ui.tooling)
}