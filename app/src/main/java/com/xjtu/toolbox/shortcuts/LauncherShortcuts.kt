package com.xjtu.toolbox.shortcuts

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.home.HomeIcons
import com.xjtu.toolbox.nav.AppRoute

internal object LauncherShortcuts {
    private const val PREFS = "launcher_shortcuts"
    private const val SELECTED = "selected_routes"
    private const val ICON_STYLE = "icon_style"

    /** 图标画法变了就加一：已有的快捷方式下次同步时跟着换。 */
    private const val ICON_STYLE_VERSION = 1
    private const val MIUI_OP_INSTALL_SHORTCUT = 10017
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iconsCurrent = prefs.getInt(ICON_STYLE, 0) == ICON_STYLE_VERSION
        val wanted = selected(context).mapNotNull { id -> candidates().firstOrNull { it.route.id == id } }
        if (iconsCurrent && manager.dynamicShortcuts.sortedBy { it.rank }.map { it.id } == wanted.map { shortcutId(it.route) }) return true
        val updated = manager.setDynamicShortcuts(wanted.mapIndexed { rank, service -> info(context, service).setRank(rank).build() })
        if (!iconsCurrent) {
            // 已经放到桌面的也换成新图标
            val pinned = manager.pinnedShortcuts.mapNotNull { shortcut ->
                candidates().firstOrNull { shortcutId(it.route) == shortcut.id }?.let { info(context, it).build() }
            }
            if (pinned.isNotEmpty()) manager.updateShortcuts(pinned)
            if (updated) prefs.edit().putInt(ICON_STYLE, ICON_STYLE_VERSION).apply()
        }
        return updated
    }

    enum class PinResult { Requested, NeedPermission, Unsupported }

    fun pin(context: Context, service: AppService): PinResult {
        if (pinPermissionDenied(context)) return PinResult.NeedPermission
        val manager = context.getSystemService(ShortcutManager::class.java)
        val requested = manager.isRequestPinShortcutSupported && manager.requestPinShortcut(info(context, service).build(), null)
        return if (requested) PinResult.Requested else PinResult.Unsupported
    }

    /**
     * MIUI / HyperOS 的「桌面快捷方式」权限（私有 op 10017）被拒时，`requestPinShortcut` 照样返回 true
     * 但桌面上什么都不会出现，只能主动查。其他系统或查询失败都按未拒绝处理。
     */
    fun pinPermissionDenied(context: Context): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return false
        return runCatching {
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.java, Int::class.java, String::class.java)
            val mode = check.invoke(context.getSystemService(AppOpsManager::class.java), MIUI_OP_INSTALL_SHORTCUT, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)
    }

    /** 打开这个 App 的权限页：MIUI 直达权限编辑页，打不开就退回系统的应用详情。 */
    fun openPermissionSettings(context: Context) {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun shortcutId(route: AppRoute) = "route-${route.id}"

    private fun info(context: Context, service: AppService): ShortcutInfo.Builder =
        ShortcutInfo.Builder(context, shortcutId(service.route))
            .setShortLabel(service.title).setLongLabel(service.title)
            .setIcon(shortcutIcon(context, HomeIcons.of(service.route)))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, service.route.id))
}
