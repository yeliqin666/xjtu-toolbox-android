package com.xjtu.toolbox.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.ServiceCategory
import com.xjtu.toolbox.nav.AppRoute

/**
 * 插件公开页在首页目录里的那一份（见 `HomeHost.extraServices`）。
 *
 * 只收**启用且没坏**的插件：
 * - dex 插件要清单里给页面标了 `home = true`（`pages[].home`）才进——插件内部的子页不该
 *   出现在首页和搜索里；
 * - web 插件只有一个网页，那本来就是它唯一的公开页，直接进。
 *
 * 卡片给 2×1：插件不做图标（2026-11 定的），1×1 是纯图标模式，一排方块分不清谁是谁，
 * 得要「页面名 + 插件名」两行。
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

/** 纯函数版（组合之外也能用，测试直接喂记录）。 */
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
                    subtitle = manifest.summary.ifBlank { "网页插件" },
                    category = ServiceCategory.TOOL,
                    aliases = listOf(manifest.name, manifest.summary).filter { it.isNotBlank() },
                    pluginPage = true,
                ),
            )
        } else {
            manifest.pages.filter { it.home }.map { page ->
                AppService(
                    route = AppRoute.Plugin(record.id, page.id),
                    title = page.title.ifBlank { manifest.name },
                    subtitle = manifest.name,
                    category = ServiceCategory.TOOL,
                    aliases = listOf(manifest.name, page.title, manifest.summary).filter { it.isNotBlank() },
                    pluginPage = true,
                )
            }
        }
    }
