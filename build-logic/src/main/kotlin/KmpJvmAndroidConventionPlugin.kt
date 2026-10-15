import org.gradle.api.Plugin
import org.gradle.api.Project

/** `toolbox.kmp.jvm-android` —— 只有 android + jvm 两个目标（okhttp / jsoup 只发 JVM 变体）。 */
class KmpJvmAndroidConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureKmpLibrary(wasm = false)
}
