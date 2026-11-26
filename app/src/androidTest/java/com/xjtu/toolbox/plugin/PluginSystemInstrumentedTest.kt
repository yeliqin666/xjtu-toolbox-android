package com.xjtu.toolbox.plugin

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备测试：把 androidTest asset 里的样例插件 APK 走一遍安装 → 加载 → 列出页面/工具 → 卸载。
 *
 * 这条测试真正覆盖的是 dex 加载和宿主/插件两侧 `PluginEntry` 的类身份；
 * 不测 `schoolTool`（依赖登录与学校网络）。
 */
@RunWith(AndroidJUnit4::class)
class PluginSystemInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = PluginManager.get(context)
    private val pluginId = "top.dzhz.plugin.sample"

    @After
    fun cleanup() {
        runBlocking { runCatching { manager.uninstall(pluginId) } }
    }

    @Test
    fun installLoadAndUninstallSamplePlugin() {
        runBlocking {
            val apk = java.io.File(context.cacheDir, "sample-plugin.apk")
            instrumentation.context.assets.open("sample-plugin.apk").use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }

            val pending = manager.inspect(Uri.fromFile(apk))
            manager.commit(pending)
            manager.setToolsEnabled(pluginId, true)
            manager.refresh()

            val opened = manager.openPage(pluginId, "home")
            assertNotNull("页面没有从插件里加载出来", opened)
            assertTrue("页面 id 不对", opened!!.page.id() == "home")

            val definitions = manager.toolDefinitions()
            val qualified = "plugin_" + pluginId.replace('.', '_') + "_demo_course_count"
            assertTrue("工具定义没有并进来：$definitions", definitions.contains(qualified))

            val missing = manager.executeTool("plugin_" + pluginId.replace('.', '_') + "_nope", "{}")
            assertTrue("未知工具应当回 not_found，实际：$missing", missing.contains("not_found"))

            manager.uninstall(pluginId)
            assertTrue("卸载后不应再有记录", manager.records.none { it.id == pluginId })
        }
    }
}
