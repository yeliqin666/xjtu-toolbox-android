package com.xjtu.toolbox.plugin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.ServiceCategory
import com.xjtu.toolbox.nav.AppRoute

/**
 * 插件页在首页目录里的那一份（见 `HomeHost.extraServices`）。
 *
 * 只收**启用且没坏**的插件：
 * - dex 插件的每个页面都进目录，除非清单里写了 `"shortcut": false`；
 * - web 插件只有一个网页，它天然就是公开页。
 *
 * 标题：**主标题是页面自己的标题，副标题是插件名**——首页卡片的大字取主标题
 * （见 `HomeServiceRow.heroIsTitle`），所以一格上看到的是「文字与样式」，小字才是「插件模板」。
 */
@Composable
internal fun rememberPluginServices(): List<AppService> {
    val context = LocalContext.current
    val manager = remember(context) { PluginManager.get(context) }
    // 首页 / 搜索第一次进入时注册表可能还没读过（插件列表页没打开过）
    LaunchedEffect(manager) { runCatching { manager.refresh() } }
    // 组合期读这两个快照状态：装、卸、启用、停用都会让首页目录跟着重算
    val records = manager.records
    val broken = manager.brokenIds
    return remember(records, broken) { pluginServices(records, broken) }
}

/** 组合之外的一次性取法（插件页右上角那个加号按钮用）。 */
internal fun pluginServicesNow(context: Context): List<AppService> {
    val manager = PluginManager.get(context)
    return pluginServices(manager.records, manager.brokenIds)
}

/** 纯函数版（测试直接喂记录）。 */
internal fun pluginServices(
    records: List<PluginRecord>,
    broken: Set<String> = emptySet(),
): List<AppService> = records
    .filter { it.enabled && it.id !in broken }
    .flatMap { record ->
        val manifest = record.manifest
        if (manifest.isWeb) {
            listOf(
                AppService(
                    route = AppRoute.PluginWeb(record.id),
                    title = manifest.name,
                    subtitle = "网页插件",
                    category = ServiceCategory.TOOL,
                    aliases = listOf(manifest.name, manifest.summary).filter { it.isNotBlank() },
                    pluginPage = true,
                ),
            )
        } else {
            manifest.pages.filter { it.shortcut }.map { page -> pluginPageService(record, page) }
        }
    }

/** 一页插件页在目录里的样子（首页、搜索、添加面板、「加到主页」按钮共用一份）。 */
internal fun pluginPageService(record: PluginRecord, page: PluginPageManifest): AppService {
    val manifest = record.manifest
    return AppService(
        route = AppRoute.Plugin(record.id, page.id),
        title = page.title.ifBlank { manifest.name },
        subtitle = manifest.name,
        category = ServiceCategory.TOOL,
        aliases = listOf(manifest.name, page.title, manifest.summary).filter { it.isNotBlank() },
        pluginPage = true,
        iconName = page.icon,
        colorHex = page.iconColor,
    )
}
