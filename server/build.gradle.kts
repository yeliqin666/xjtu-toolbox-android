// :server —— **serve 模式**（`docs/desktop-port-plan.md` D3 的另一半）：无窗口的常驻进程，
// 用 Ktor 托管 `:web` 的 wasm 产物 + 实现 `/api/*`。
//
// ## 它为什么是纯 JVM 模块
//
// serve 模式只跑在**部署那台机器**上（Linux / Windows 的服务器或常驻进程），不需要 wasm/ios 目标，
// 而 `application` 插件（`./gradlew :server:run`）在 `kotlin("jvm")` 上是官方模板形态 ——
// 与 `:desktop` 同一个理由。
//
// ## 它为什么不是「一个端」
//
// 设计文档 §3 那张图里，serve 模式 = **同一份 `:data`** + **同一份 `:web`（浏览器里的 UI）**
// + 这一层 HTTP 外壳。所以这里没有业务：
//   - 数据层是 `:data` 的（会话内核、取数、解析口径全在那边，有测试钉着）；
//   - UI 是 `:web` 的（这里的静态托管只读它的产物目录，**不往仓库里拷**）；
//   - `:core` 只用到 `AppJson`（出网 JSON 与客户端同一份配置）。
//
// 契约（要暴露什么形状）以 `docs/api-contract.md` 为准；本模块是它的实现。
//
// ## 依赖与引擎选择
//
// CIO 而不是 Netty：纯 Kotlin 协程实现、没有 native 传输层依赖，体积与内存都小得多
//（这台机器 7.3G 内存 / swap 已吃满，见 ~/.gradle/gradle.properties 里的说明），
// 而 serve 模式的并发量是「一个人的浏览器」，CIO 远远够用。
plugins {
    // 版本在根工程 build.gradle.kts 里 apply false 钉死（与 :desktop 同一条纪律）
    alias(libs.plugins.kotlin.jvm)
    // `./gradlew :server:run --args="--port 18234 --dist …"`
    application
    // 信封与 /api/status 的 @Serializable 模型
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 与 :app / :core / :data / :desktop 对齐：JDK 21 工具链
    jvmToolchain(21)
}

dependencies {
    // 出网 JSON 的配置（AppJson）：契约里的形状与客户端读的 JSON 是同一套口径
    implementation(project(":core"))
    // 数据层与会话内核 —— serve 模式的「后端」就是它（不再有 campus-api）
    implementation(project(":data"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // ── test ─────────────────────────────────────────────────────────────────────
    // 只起真 HTTP（临时端口 + 真 socket）：绑定地址、令牌闸门、静态托管这三条都只能在
    // 真 socket 上验。客户端用 JDK 自带的 java.net.http（不引新依赖，也不受 ktor-client 的
    // 默认行为挡住「不带令牌的请求」这件事）。
    testImplementation(kotlin("test"))
    // JUnit4：`kotlin("test")` 在 kotlin-jvm 上默认落到 kotlin-test-junit，引擎要显式给
    testImplementation(libs.junit)
    // 假校园上游（`:testkit`）：`/api/session*` 的契约测试要**真登录**一次（真 CAS 表单 POST、
    // 真 ticket 回跳、真短信二验），那套上游只能来自它。它**绝不进交付物** —— 只挂 test 源集
    // （`implementation(project(":testkit"))` 会把一个「任何密码都收」的假统一认证打进 .deb）。
    testImplementation(project(":testkit"))
}

application {
    mainClass.set("com.xjtu.toolbox.server.MainKt")
}

/**
 * `run` 的工作目录固定成**仓库根**。
 *
 * 为什么必须显式写：`JavaExec.workingDir` 默认是**本工程目录**（`server/`），而 `--dist` 的默认值
 * 是仓库根相对路径 `web/build/dist/wasmJs/productionExecutable`。不改这一行，
 * `sh gradlew :server:run --args="--port 18234"` 会对着 `server/web/build/...` 找产物（永远找不到）。
 */
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
