import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `toolbox.kmp.compose` —— 在 `toolbox.kmp.library` 之上加 Compose Multiplatform + MIUIX 整套 UI 栈。
 * 依赖清单与原 `:core` 的 commonMain 一致（android 变体转发到 androidx.compose，与 :app 的 BOM 同版）。
 */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.compose")
            apply("org.jetbrains.kotlin.plugin.compose")
        }
        val compose = extensions.getByType(ComposeExtension::class.java).dependencies
        val catalog = libs
        val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.sourceSets.getByName("commonMain").dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.animation)
            // MIUIX 的 KMP 根模块（无 -android 后缀）。
            api(catalog.lib("miuix-ui-kmp"))
            api(catalog.lib("miuix-icons-kmp"))
            api(catalog.lib("miuix-nav-kmp"))
            api(catalog.lib("miuix-preference-kmp"))
            api(catalog.lib("miuix-squircle-kmp"))
            // JetBrains 版 lifecycle，与 :app 的 androidx.lifecycle 同版。
            api(catalog.lib("jb-lifecycle-viewmodel"))
            api(catalog.lib("jb-lifecycle-viewmodel-compose"))
            api(catalog.lib("jb-lifecycle-runtime-compose"))
            api(catalog.lib("kyant-backdrop"))
            api(catalog.lib("compose-material-icons-core"))
            // 跨端图标：CMP 多平台版停在 1.7.3。
            api(catalog.lib("compose-material-icons-extended"))
            api(catalog.lib("compose-ui-backhandler"))
        }
    }
}
