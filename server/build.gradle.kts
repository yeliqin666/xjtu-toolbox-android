// :server —— serve 模式：用 Ktor（CIO）托管 `:web` 的 wasm 产物并实现 `/api/*`（契约见 docs/api-contract.md）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    // `./gradlew :server:run --args="--port 18234 --dist …"`
    application
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // JDK 21 工具链
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    // JUnit4：kotlin-jvm 上 `kotlin("test")` 要显式给引擎
    testImplementation(libs.junit)
    // ⚠️ `:testkit` 只能挂 test 源集：它的假统一认证收任何密码。
    testImplementation(project(":testkit"))
    // 黄页是 Ktor 接口 ⇒ 用 MockEngine 喂夹具响应
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("com.xjtu.toolbox.server.MainKt")
}

/** `run` 的工作目录固定成仓库根。 */
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.named<Test>("test") {
    val root = layout.buildDirectory.dir("testStoreRoot")
    doFirst { root.get().asFile.deleteRecursively() }
    systemProperty("xjtu.toolbox.keyValueRoot", root.get().asFile.absolutePath)
}
