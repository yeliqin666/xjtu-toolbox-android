import org.gradle.api.Plugin
import org.gradle.api.Project

/** `toolbox.kmp.library` —— android + jvm + wasmJs（浏览器）三端的 KMP 库。 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureKmpLibrary(wasm = true)
}
