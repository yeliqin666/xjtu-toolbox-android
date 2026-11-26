import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
    // 不影响设备兼容范围。
    compileSdk {
        version = release(37) {
        }
    }

    defaultConfig {
        applicationId = "com.xjtu.toolbox"
        // Android 12 起可装；低于 Android 15 时玻璃特效统一退回朴素外观（见 GlassSupport），PDF 取文字不可用
        minSdk = 31
        targetSdk = 36
        versionCode = 101
        versionName = "6.2.3"

        // 插件系统的设备测试：样例 APK 作为 androidTest asset，见文件末尾的 preparePluginTestAssets
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 反馈后端（飞书多维表格）的凭据。优先读环境变量（CI），否则读
        // 仓库根目录 feedback.properties（已 gitignore）。两者都没有时留空字符串——
        // FeedbackApi.isConfigured 为 false，反馈页降级成 GitHub Issue。
        //
        // CI 用 GitHub Actions secrets，名字与 BuildConfig 字段一致：
        // FEEDBACK_APP_ID / FEEDBACK_APP_SECRET / FEEDBACK_BASE_TOKEN /
        // FEEDBACK_TABLE_SUBMIT / FEEDBACK_TABLE_REPLY。
        // 也认旧写法 FEEDBACK_APPID（camelCase 键直接大写、没有下划线）。
        //
        // app_secret 终究是打进 APK 的，无法真正保密：这对凭据只有记录的检索与新增权限（没有删除），
        // 拿到的人能读到两张表全部内容。反馈本来就会公开到 GitHub 日报，所以不再放联系方式。
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

            // 小游戏全服榜的服务端地址（服务端在独立仓库 Guochaoo/dzhz-server 的 services/leaderboard）。
            //
            // 新版本直连 Cloudflare Worker（api.dzhz.top/games/*）；老版本里写死的
            // dzhz.zyxf.top/games 已在源站返回 410 JSON 提示升级——实测客户端 HTTP 栈不跟随 307，
            // 所以不能靠重定向把老版本迁过去。
            //
            // 默认指向正式环境，这是公开地址、不是密钥，所以直接写在这里而不是塞进
            // 某个 properties 文件——CI 和普通克隆都该开箱即用。本地联调要指到自己的
            // 服务端时，用环境变量 LEADERBOARD_BASE_URL，或在仓库根建
            // leaderboard.properties（已 gitignore）写 baseUrl=http://127.0.0.1:8787/games
            // （Worker 版 wrangler dev），配合 adb reverse tcp:8787 tcp:8787。
            //
            // 置成空字符串 = 整块排行榜功能从界面上消失（Leaderboard.enabled 为 false）。
            val lbFile = rootProject.file("leaderboard.properties")
            val lbProps = Properties().apply { if (lbFile.exists()) lbFile.inputStream().use { load(it) } }
            val lbUrl = System.getenv("LEADERBOARD_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
                ?: lbProps.getProperty("baseUrl")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "https://api.dzhz.top/games"
            buildConfigField("String", "LEADERBOARD_BASE_URL", "\"$lbUrl\"")
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
            // V3 签名（API≥28）支持密钥轮换
            enableV3Signing = true
        }
    }

    // 预览后缀：CI 传 -PpreviewSuffix=beta.N（N 由工作流按同一核心版本已发布的 pre-release
    // 算出，见 .github/workflows/build.yml「Validate dev version and compute preview version」）。
    // 两条预览路径共用这一份后缀：
    // - preview 变体（日常预览）：不传属性就是本地构建，标成 -dev.local，不冒充 beta；
    // - release 变体（发版前的真机检查，CI 勾 release_preview）：只在传了属性时补后缀，
    //   main 上的正式发布不传，版本号原样。
    val previewSuffix = providers.gradleProperty("previewSuffix").orNull?.takeIf { it.isNotBlank() }
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
            // 又不想卸载正式版丢数据）。动态快捷方式会使用当前包名。
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
            // 发版前想拿正式构建的方式连验几轮：CI 传 -PpreviewSuffix=beta.N，
            // 正式包的版本号就补上预览后缀（6.0.1 → 6.0.1-beta.N），仍低于同号正式版，
            // 正式发布时不带这个参数，版本号原样。
            previewSuffix?.let { versionNameSuffix = "-$it" }
        }
        create("preview") {
            // 预览渠道包：给愿意尝鲜的用户，也是我们抓问题的主要来源。
            // - R8 与正式版完全同一套规则（initWith(release) 继承 proguardFiles）：
            //   混淆 + 资源压缩 + 移除 Log.d/v，包体和运行表现对齐正式版，
            //   也能提前暴露只在 R8 后才出现的 keep / 序列化问题。
            //   代价：崩溃堆栈需要 mapping retrace，CI 会按预览版本号上传 mapping。
            // - 非 debuggable：debug 包的 Compose 明显卡顿，用户会以为新版本变慢。
            // - 与 release 同签名：可与正式版互相覆盖安装，不丢登录态。
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // initWith 已把 release 的签名配置抄过来；本地没有 keystore 时要显式清掉，
            // 否则 validateSigningPreview 直接失败，而不是产出未签名包。
            // CI 上一定有 keystore，Verify preview APK 会拦住未签名的情况。
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            // 依赖库只有 debug/release 两种变体，preview 找不到时回落到 release。
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "IS_PREVIEW", "true")
            // CI 上由 -PpreviewSuffix=beta.N 给出；本地直接 assemblePreview 则是 6.0.1-dev.local
            versionNameSuffix = "-${previewSuffix ?: "dev.local"}"
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
    // 正式发布包与预览包都只带 ARM 的 so（CameraX、graphics-path 各一份 x86/x86_64，约 170KB）：
    // 手机与平板都是 ARM。只挑 release / preview 这两个构建类型——baseline profile 插件派生的
    // nonMinifiedRelease / nonMinifiedPreview 跑在 x86_64 托管模拟器上，必须保留 x86_64，
    // 否则采集时加载 so 失败。
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
    }
    onVariants(selector().withBuildType("preview")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
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

// 只用来下载 Robolectric 的 android-all，见文件末尾
val robolectricAndroidAll: Configuration by configurations.creating

dependencies {
    // 三端共用的业务与平台抽象（KMP）；:core 必须能编过 wasmJs（CI 门禁在守）。
    implementation(project(":core"))
    implementation(project(":data"))
    // `:core` 用 api 转发了全部 :feature:*，但**别依赖那层转发**：:app 的源码是直接按包名
    // 引用 feature 里的类（@Serializable 路由、各 feature 自带的 AgentTools、archive 那套……），
    // 一旦转发对 Android 消费者没生效，报的全是「包能解析、类找不到」的 Unresolved，
    // 排查起来还很绕。这里把用到的 feature 直接声明一遍，compose 的依赖本来就该显式。
    implementation(project(":feature:account"))
    implementation(project(":feature:agent"))
    implementation(project(":feature:attendance"))
    implementation(project(":feature:campus"))
    implementation(project(":feature:community"))
    implementation(project(":feature:game"))
    implementation(project(":feature:home"))
    implementation(project(":feature:jiaocai"))
    implementation(project(":feature:library"))
    implementation(project(":feature:lms"))
    implementation(project(":feature:notice"))
    implementation(project(":feature:schedule"))
    implementation(project(":feature:score"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:webvpn"))
    // 插件系统的稳定 API；插件 APK 也编译时依赖它（见 plugin/api）
    implementation(project(":plugin:api"))
    // 金标联盟 SharedViewKit：ROM 声明支持时，公告卡 → 公告详情走系统级一镜到底；
    // 不支持时由 :app 的 KitOneTake 回落成 App 内现有的 Compose 转场（见 KitOneTake.kt）。
    implementation(libs.sharedview.kit)
    implementation(libs.androidx.core.ktx)
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
    implementation(libs.coil.svg)
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.java.net.cookiejar)
    implementation(libs.jsoup)
    implementation(libs.flexmark.html2md)
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
    // MIUIX：版本钉在 libs.versions.toml（0.9.4，正式版），升级是显式改版本号
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // 超椭圆圆角（MIUI/iOS 那种平滑拐角）
    implementation(libs.miuix.squircle)
    // 导航运行时（连续栈、跟手侧滑返回、预测式返回）
    implementation(libs.miuix.nav)
    implementation(libs.kotlinx.serialization.json)
    // 液态玻璃（Kyant0/AndroidLiquidGlass，Apache-2.0）
    implementation(libs.kyant.backdrop)
    // 形状形变（加载动画）
    implementation(libs.androidx.graphics.shapes)
    // Ktor 的 OkHttp 引擎预置现有 OkHttpClient：cookie jar、UA 拦截器、超时、TLS、连接池全部沿用。
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    robolectricAndroidAll("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    debugImplementation(libs.androidx.ui.tooling)
    // 组合追踪：广播 androidx.tracing.perfetto.action.ENABLE_TRACING 后，Perfetto 的 track_event 里有每个重组的组件名
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto)
    debugImplementation(libs.androidx.tracing.perfetto.binary)
}

// Robolectric 默认在测试运行时自己从 Maven Central 拉 android-all（约 200 MB，不重试、不进 Gradle 缓存），
// CI 上常因网络抖动挂。改由 Gradle 解析下载（有重试，进 setup-gradle 的缓存），测试离线读这个目录。
// 版本是 Robolectric 4.17 里 SDK 35 对应的那份（测试都 @Config(sdk = [35])）；升 Robolectric 或改 sdk 时一起改，
// 漏了测试会报找不到依赖，不会静默联网。
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val syncRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricAndroidAll)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}
