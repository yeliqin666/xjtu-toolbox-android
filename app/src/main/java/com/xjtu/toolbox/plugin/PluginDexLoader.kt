package com.xjtu.toolbox.plugin

import dalvik.system.DexClassLoader
import java.io.File

/**
 * 插件 APK 的 classloader。
 *
 * 宿主 R8 会改名 `androidx.compose.*` / `kotlin.*` / `kotlinx.*`，所以这些包必须由插件自带的
 * 副本胜出（child-first）；`plugin.api`、Android 框架、`java.*` 仍走父加载器（parent-first），
 * 这样 `PluginEntry` 等接口在两边是同一个类。
 */
internal class PluginDexLoader(
    apkPath: String,
    dexDir: File,
    parent: ClassLoader,
) : DexClassLoader(apkPath, dexDir.absolutePath, null, parent) {

    // lifecycle / savedstate 走父加载器：插件 ComposeView 需要和宿主 AndroidView 共用
    // LifecycleOwner 的类身份与 R.id.view_tree_lifecycle_owner，否则 findViewTreeLifecycleOwner
    // 读不到宿主/插件任一边设的 tag。其余 androidx（Compose/core/activity…）仍用插件自带副本。
    private val childFirst = listOf(
        "androidx.compose.",
        "androidx.activity.",
        "androidx.core.",
        "androidx.collection.",
        "androidx.annotation.",
        "androidx.tracing.",
        "androidx.profileinstaller.",
        "androidx.graphics.",
        // kotlin. 走父加载器：宿主的 lifecycle/viewmodel 代码会用宿主的 Kotlin stdlib
        // 处理插件传过去的 KClass；两边 stdlib 不同会 ClassCastException。
        "kotlinx.",
        "org.jetbrains.",
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (childFirst.any { name.startsWith(it) }) {
            synchronized(this) {
                findLoadedClass(name)?.let { return it }
                try {
                    return findClass(name)
                } catch (_: ClassNotFoundException) {
                    // 插件没打包这个类，退回父加载器（例如 kotlin 标准库的少数类）。
                }
            }
        }
        return super.loadClass(name, resolve)
    }
}
