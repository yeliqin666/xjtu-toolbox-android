package com.xjtu.toolbox.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.nav.AppRoute

internal object LauncherShortcuts {
    private const val PREFS = "launcher_shortcuts"
    private const val SELECTED = "selected_routes"
    private val defaults = listOf("payment_code", "agent", "schedule", "campus_card")

    fun candidates(): List<AppService> = AppServices.homeFor(AccountContext.activeAccountType)

    fun limit(context: Context): Int = minOf(4, context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity)

    fun selected(context: Context): List<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED, null)
        return (saved?.split(',') ?: defaults).filter { id -> candidates().any { it.route.id == id } }.distinct().take(limit(context))
    }

    fun save(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SELECTED, ids.joinToString(",")).apply()
    }

    fun sync(context: Context): Boolean {
        AccountContext.restoreIfNeeded()
        val manager = context.getSystemService(ShortcutManager::class.java)
        val wanted = selected(context).mapNotNull { id -> candidates().firstOrNull { it.route.id == id } }
        if (manager.dynamicShortcuts.sortedBy { it.rank }.map { it.id } == wanted.map { shortcutId(it.route) }) return true
        return manager.setDynamicShortcuts(wanted.mapIndexed { rank, service -> info(context, service).setRank(rank).build() })
    }

    fun pin(context: Context, service: AppService): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        return manager.isRequestPinShortcutSupported && manager.requestPinShortcut(info(context, service).build(), null)
    }

    private fun shortcutId(route: AppRoute) = "route-${route.id}"

    private fun info(context: Context, service: AppService): ShortcutInfo.Builder {
        val icon = when (service.route) {
            AppRoute.Agent -> R.drawable.shortcut_agent_icon
            AppRoute.PaymentCode -> R.drawable.shortcut_payment_icon
            AppRoute.Schedule -> R.drawable.shortcut_schedule_icon
            AppRoute.CampusCard -> R.drawable.shortcut_campus_card_icon
            AppRoute.EmptyRoom -> R.drawable.shortcut_empty_room_icon
            else -> R.mipmap.ic_launcher
        }
        return ShortcutInfo.Builder(context, shortcutId(service.route))
            .setShortLabel(service.title).setLongLabel(service.title)
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, service.route.id))
    }
}
