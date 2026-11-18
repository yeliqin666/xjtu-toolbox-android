package com.xjtu.toolbox.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xjtu.toolbox.platform.Log

private const val PREFS = "attendance_widget_sync"
private const val KEY_SCREEN_ON = "screen_on"

/**
 * 桌面卡片「现在大概正被人看着吗」。
 *
 * 安卓没有「小组件当前可见」这种回调：`AppWidgetProvider` 只在加/删/改尺寸时被叫，
 * 用户划到桌面不会通知我们。所以只能拿屏幕状态当代理：
 *
 * - 熄屏：肯定没人看 ⇒ **一次都不查**（省电的大头：夜里和揣兜里时全静默）；
 * - 亮屏：可能正看着 ⇒ 才按档自查。
 *
 * 不查 `KeyguardManager.isDeviceLocked`：那是一次额外的系统服务调用，而锁屏时卡片本来也
 * 不会被人看，多这一问并不能省下多少；少一个系统调用、少一份状态就少一处出错的地方。
 */
internal object WidgetVisibility {

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 由 [ScreenStateReceiver] 在亮/灭屏时写入。 */
    internal fun setScreenOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_ON, on).apply()
    }

    /**
     * 默认按「亮着」处理：进程刚起来、系统还没广播过屏幕状态时，宁可多查一次也别漏掉
     * —— 这个判断只用来省电，不能用来保证正确性。
     */
    fun isLikelyVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCREEN_ON, true)
}

/**
 * 亮屏 / 灭屏：只做两件事，**绝不主动发网络请求**。
 *
 * 为什么不在这时候去问学校：人一天亮屏几十次，每次都问一遍既费电也白打学校接口。
 * 亮屏只把本地那份状态重画一下（几毫秒），真正的自查仍由闹钟按档驱动
 * （见 `SmartWidgetUpdater.attendancePollInterval`）；想立刻知道就点卡片上的「刷新」。
 */
class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            // 熄屏后还排着的自查闹钟就是纯浪费：取消掉。下次亮屏重画会把闹钟重新排上。
            Intent.ACTION_SCREEN_OFF -> {
                WidgetVisibility.setScreenOn(app, false)
                runCatching { SmartWidgetUpdater.cancel(app) }
            }
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                WidgetVisibility.setScreenOn(app, true)
                runCatching { SmartWidgetUpdater.requestUpdate(app) }
                    .onFailure { Log.d("SmartWidget", "亮屏重画失败：${it.message}") }
            }
        }
    }
}
