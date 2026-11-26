package com.xjtu.toolbox.plugin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.HomeGroup
import com.xjtu.toolbox.home.HomeLayout
import com.xjtu.toolbox.home.HomeLayoutDefaults
import com.xjtu.toolbox.home.HomeLayoutRevision
import com.xjtu.toolbox.home.HomeLayoutStore

/**
 * 插件页 → 首页快捷方式：查询 / 加一格 / 去一格。
 *
 * 直接改首页那份布局存档（和首页编辑态点「对勾」是同一个语义）：没摆过首页的人先把默认布局
 * 铺一份再改，加的块统一进「插件」组。`save()` 会 bump [HomeLayoutRevision]，首页自己重读。
 */
internal object HomeShortcuts {

    fun isOnHome(context: Context, routeId: String): Boolean =
        loaded(context).groups.any { group -> group.tiles.any { it.routeId == routeId } }

    fun add(context: Context, service: AppService) {
        val store = HomeLayoutStore()
        var layout = loaded(context)
        if (layout.groups.any { group -> group.tiles.any { it.routeId == service.route.id } }) return
        val groupId = HomeLayoutDefaults.PLUGIN_GROUP
        if (layout.group(groupId) == null) layout = layout.addGroup(HomeGroup(id = groupId, title = "插件"))
        layout = layout.addTile(groupId, service.route.id, layout.nextTileId(groupId, service.route.id), 2f, 1f)
        store.save(layout)
    }

    fun remove(context: Context, routeId: String) {
        val store = HomeLayoutStore()
        var layout = loaded(context)
        val hits = layout.groups.flatMap { group -> group.tiles.filter { it.routeId == routeId } }
        if (hits.isEmpty()) return
        hits.forEach { tile -> layout = layout.removeTile(tile.id, store.grid) }
        store.save(layout)
    }

    /** 当前应当渲染的那份布局（存档优先，没有就按目录铺默认）。 */
    private fun loaded(context: Context): HomeLayout = HomeLayoutStore().load(
        // 和启动器快捷方式（LauncherShortcuts）同一个取法：进程里当前账号的身份
        accountType = AccountContext.activeAccountType,
        quickRoutes = emptyList(),
        extra = pluginServicesNow(context),
    )
}

/** 这一页现在在不在首页上；布局或插件列表变了会跟着重组。 */
@Composable
internal fun rememberOnHome(routeId: String): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { PluginManager.get(context) }
    val records = manager.records
    val revision = HomeLayoutRevision.current
    return remember(routeId, records, revision) { HomeShortcuts.isOnHome(context, routeId) }
}
