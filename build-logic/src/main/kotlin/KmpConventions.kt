import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** `:shared:data` → `shared-data`：模块名 / 产物名都要全局唯一（`:data` 与 `:shared:data` 同名会撞 kotlin_module 和 jar）。 */
internal val Project.flatName: String
    get() = path.removePrefix(":").replace(':', '-')

/**
 * android（AGP 9 KMP 库插件）+ jvm（+ 可选 wasmJs）三端的公共配置，与原 `:core` / `:data` 的写法逐项一致：
 * JVM 21、compileSdk 37、minSdk 31、namespace = com.xjtu.toolbox.<模块路径>。
 */
internal fun Project.configureKmpLibrary(wasm: Boolean) {
    with(pluginManager) {
        apply("org.jetbrains.kotlin.multiplatform")
        apply("com.android.kotlin.multiplatform.library")
        apply("org.jetbrains.kotlin.plugin.serialization")
    }

    pluginManager.apply("base")
    extensions.configure(BasePluginExtension::class.java) { archivesName.set("toolbox-$flatName") }

    val kmp = extensions.getByType(KotlinMultiplatformExtension::class.java)
    // 与 :app 对齐 JVM 21：miuix-nav 的 inline 函数要求字节码目标一致。
    kmp.jvmToolchain(21)

    val kotlinExtensions = (kmp as ExtensionAware).extensions
    val android = (kotlinExtensions.findByName("android") ?: kotlinExtensions.getByName("androidLibrary"))
        as KotlinMultiplatformAndroidLibraryTarget
    android.namespace = "com.xjtu.toolbox." + path.removePrefix(":").replace(':', '.')
    android.compileSdk = 37
    android.minSdk = 31

    kmp.jvm()

    if (wasm) kmp.wasmBrowser()

    val catalog = libs
    kmp.sourceSets.getByName("commonMain").dependencies {
        implementation(catalog.lib("kotlinx-coroutines-core"))
        implementation(catalog.lib("kotlinx-serialization-json"))
        // 用 api：公开签名里到处是 LocalDate / Instant。
        api(catalog.lib("kotlinx-datetime"))
    }
    kmp.sourceSets.getByName("commonTest").dependencies {
        implementation(kotlinModule("test"))
        implementation(catalog.lib("kotlinx-coroutines-test"))
    }

    // 模块名唯一：否则两个都叫 `data` 的模块会在 APK 里撞 META-INF/*.kotlin_module。
    val moduleName = "toolbox-$flatName"
    tasks.withType(KotlinJvmCompile::class.java).configureEach {
        if (!name.contains("Test")) compilerOptions.moduleName.set(moduleName)
    }

    // 键值落盘根：每次 jvmTest 前清空（与原 :core / :data 一致）。
    tasks.withType(Test::class.java).configureEach {
        if (name == "jvmTest") {
            val root = layout.buildDirectory.dir("testStoreRoot")
            doFirst { root.get().asFile.deleteRecursively() }
            systemProperty("xjtu.toolbox.keyValueRoot", root.get().asFile.absolutePath)
        }
    }
}

private fun kotlinModule(module: String) = "org.jetbrains.kotlin:kotlin-$module"

/** 不声明 wasm 测试环境：依赖 Compose ui-graphics 后 nodejs 测试会因 skiko.mjs 报 ERR_MODULE_NOT_FOUND。 */
@OptIn(ExperimentalWasmDsl::class)
private fun KotlinMultiplatformExtension.wasmBrowser() {
    wasmJs { browser() }
}
