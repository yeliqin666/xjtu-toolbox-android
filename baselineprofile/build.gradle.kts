plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.xjtu.toolbox.baselineprofile"
    // 与 :app 一致：composite build 里 miuix 的 AAR metadata 要求 compileSdk 不低于 37
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // macrobenchmark 要求 minSdk ≥ 28；跟着 :app 的 31 走即可
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 被测应用。插件据此自动生成 :app 的 nonMinifiedRelease 变体来跑采集——
    // 必须是不混淆但已 AOT 路径一致的包，否则采到的类名对不上 release。
    targetProjectPath = ":app"

    // Gradle Managed Device：跑在 Gradle 自己下载/托管的模拟器镜像上，不占用真机，
    // 也不需要真机解锁常驻。benchmark 1.5.0（≥1.2.0-alpha06）在 API 33+ 上生成
    // Baseline Profile 不需要 root，systemImageSource 用普通 "aosp" 即可。
    testOptions.managedDevices.localDevices {
        register("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

// 改为在 Gradle Managed Device 上采集，不再依赖连接的真机。
baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
