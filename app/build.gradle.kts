import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // miuix-nav 的返回栈（NavKey 路由）要求 @Serializable，进程被杀后靠它恢复
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // 消费 :baselineprofile 生成的 profile，并自动建出采集/压测用的
    // nonMinifiedRelease、benchmarkRelease 两个变体
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.xjtu.toolbox"
    // compileSdk 跟随 miuix（其 AAR metadata 声明 minCompileSdk = 37）：
    // 消费方的 compileSdk 低于它时 CheckAarMetadata 直接失败。这只是「用哪套 SDK 编译」，
    // 不影响设备兼容范围。minSdk / targetSdk 保持不动。
    compileSdk {
        version = release(37) {
        }
    }

    defaultConfig {
        applicationId = "com.xjtu.toolbox"
        minSdk = 31
        targetSdk = 36
        versionCode = 85
        versionName = "5.0.8"

        // 反馈后端（飞书多维表格）的凭据。优先读环境变量（CI），否则读
        // 仓库根目录 feedback.properties（已 gitignore）。两者都没有时留空字符串——
        // FeedbackApi.isConfigured 为 false，反馈页降级成 GitHub Issue。
        //
        // CI 用 GitHub Actions secrets，名字与 BuildConfig 字段一致：
        // FEEDBACK_APP_ID / FEEDBACK_APP_SECRET / FEEDBACK_BASE_TOKEN /
        // FEEDBACK_TABLE_SUBMIT / FEEDBACK_TABLE_REPLY。
        // 也认旧写法 FEEDBACK_APPID（camelCase 键直接大写、没有下划线）。
        //
        // app_secret 终究是打进 APK 的，无法真正保密。安全性靠"这对凭据能干什么"来兜：
        // 应用只被授权写反馈表 + 读回复表，回复表里不含用户原文。详见 FeedbackApi 注释。
        run {
            val fp = rootProject.file("feedback.properties")
            val props = Properties().apply { if (fp.exists()) fp.inputStream().use { load(it) } }
            fun cfg(key: String): String {
                val underscored = key.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
                val glued = key.uppercase()
                listOf("FEEDBACK_$underscored", "FEEDBACK_$glued").distinct().forEach { name ->
                    System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return props.getProperty(key)?.trim().orEmpty()
            }
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
            buildConfigField("boolean", "IS_PREVIEW", "false")
            // 用 release 的签名给 debug 包签名。
            // 目的：签名一致才能直接覆盖安装设备上已有的 release 版，不必先卸载——
            // 卸载会连登录态和缓存一起清掉，排查问题时每次都要重登，很折腾。
            // debug 不开 minify，proguard 里那条 -assumenosideeffects 也就不生效，
            // Log 会完整保留，这正是抓日志需要的。
            // 没配 release 签名时（例如 CI 上没有 keystore）保持默认 debug 签名。
            signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
                ?.let { signingConfig = it }
            // 带 -PdebugSuffix 构建时包名加 .debug 后缀，与设备上已装的正式版共存。
            // 场景：没有 release.jks 的机器（签名必然与正式版不同，覆盖安装会被拒，
            // 又不想卸载正式版丢数据）。代价：res/xml/shortcuts.xml 的
            // targetPackage 硬编码了主包名，共存包的长按快捷方式会失效。
            if (project.hasProperty("debugSuffix")) {
                applicationIdSuffix = ".debug"
            }
        }
        release {
            buildConfigField("boolean", "IS_PREVIEW", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        create("preview") {
            // 预览渠道包：给愿意尝鲜的用户，也是我们抓问题的主要来源。
            // - 不混淆不压缩：崩溃堆栈直接可读，proguard 里 -assumenosideeffects 不生效，Log 完整保留。
            // - 非 debuggable：debug 包的 Compose 明显卡顿，用户会以为新版本变慢。
            // - 与 release 同签名：可与正式版互相覆盖安装，不丢登录态。
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            // initWith 已把 release 的签名配置抄过来；本地没有 keystore 时要显式清掉，
            // 否则 validateSigningPreview 直接失败，而不是产出未签名包。
            // CI 上一定有 keystore，Verify preview APK 会拦住未签名的情况。
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            // 依赖库只有 debug/release 两种变体，preview 找不到时回落到 release。
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "IS_PREVIEW", "true")
            versionNameSuffix = "-dev.${System.getenv("GITHUB_RUN_NUMBER") ?: "local"}"
        }
    }
    // Java / Kotlin 目标 21：miuix 各模块都用 JDK 21 工具链编译，miuix-nav 的 entry<T>() 是 inline 函数，
    // Kotlin 不允许把 21 的字节码内联进更低目标的代码。Android 上由 D8 脱糖，不影响 minSdk。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // App 只有中英文；依赖库带进来的其他几十种语言不打包
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        // minSdk≥28 时 AGP 默认把 dex 不压缩、按页对齐存进 APK，好让 ART 直接 mmap。
        // 本应用 dex 约 8.8MB，占 APK 八成，用户却是整包下载（Gitee/GitHub Release），
        // 压缩后下载体积约 10.8→6.4MB。代价是安装时 ART 把 dex 解到 vdex、多占约 9MB
        // 存储；运行时跑的是 AOT/JIT 产物，启动速度不受影响（同机交替 A/B 各 18 次冷启动，
        // 压缩 ~335ms vs 不压缩 ~332ms，差异在噪声内）。
        dex { useLegacyPackaging = true }
    }
}

androidComponents {
    // 正式发布包只带 ARM 的 so（CameraX、graphics-path 各一份 x86/x86_64，约 110KB）：
    // 手机与平板都是 ARM。只作用于 `release` 这一个构建类型——baseline profile 插件派生的
    // nonMinifiedRelease 跑在 x86_64 托管模拟器上，必须保留 x86_64，否则采集时加载 so 失败。
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
    }
}

// miuix 走 latest.release：拒掉预发布版本；解析到的最新版本号缓存 30 分钟，
// 既能跟上新版本，又不会因为一次网络抖动让构建失败
configurations.configureEach {
    resolutionStrategy {
        cacheDynamicVersionsFor(30, TimeUnit.MINUTES)
        componentSelection.all {
            if (candidate.group == "top.yukonga.miuix.kmp" &&
                Regex("(?i)(alpha|beta|rc|dev|snapshot)").containsMatchIn(candidate.version)
            ) {
                reject("miuix 只跟正式版")
            }
        }
    }
}

// 插件只会把 profile 打进非 debuggable 变体（release），debug 不做 AOT、拿它没用。
baselineProfile {
    // 每次构建都重新采集会要求构建机常驻真机——本项目的 CI 没有设备，
    // 所以用「生成的 profile 提交进仓库」的模式，靠手动跑 generateBaselineProfile 刷新。
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // 按 startup profile 把启动要用的类排进主 dex，冷启动少读页
    dexLayoutOptimization = true
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
    // 社区（GitHub Discussions）：Markdown 渲染用不依赖 Material 3 的核心模块，配色取自 MIUIX；
    // 头像和帖子图片走 Coil，网络层复用 OkHttp 4.12
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.java.net.cookiejar)
    implementation(libs.jsoup)
    implementation(libs.flexmark.html2md)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
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
    // 思源直播是 HLS(m3u8)：ExoPlayer 靠反射加载 HlsMediaSource.Factory，缺这个模块会 ClassNotFound 崩溃
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    // 本应用靠 GitHub/Gitee Release 侧载分发，走不到 Play 的安装期 AOT。
    // profileinstaller 在首启时把包里的 baseline-prof.txt 交给 ART 编译，
    // 少了它 profile 等于没打。
    implementation(libs.androidx.profileinstaller)
    // profile 的来源模块；只是数据依赖，不进 APK
    baselineProfile(project(":baselineprofile"))
    ksp(libs.androidx.room.compiler)
    // MIUIX：版本见 libs.versions.toml，始终取 Maven Central 最新正式版
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // 超椭圆圆角（MIUI/iOS 那种平滑拐角）。设备不支持 RuntimeShader 时库内部
    // 自动退回普通 RoundedCornerShape，minSdk 31 上安全。
    implementation(libs.miuix.squircle)
    // 导航运行时（连续栈、跟手侧滑返回、预测式返回）
    implementation(libs.miuix.nav)
    implementation(libs.kotlinx.serialization.json)
    // 液态玻璃（Kyant0/AndroidLiquidGlass，Apache-2.0）
    implementation(libs.kyant.backdrop)
    // 形状形变（加载动画）
    implementation(libs.androidx.graphics.shapes)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
