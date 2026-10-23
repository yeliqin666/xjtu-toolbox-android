package com.xjtu.toolbox.desktop.notify

import com.xjtu.toolbox.platform.Log
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

private const val TAG = "DesktopNotify"

/** 托盘图标的边长（像素）：16 是各桌面环境状态区的通行尺寸。 */
private const val ICON_PX = 16

/**
 * 桌面这一端唯一的系统通知载体：AWT 的 `SystemTray`（JDK 自带，**不引任何新依赖**）。
 *
 * 它只干一件事 —— 弹一条托盘气泡。不做点开跳转（托盘气泡点了没有回调可言，桌面也没有
 * 一条「通知 → 某屏」的路由），不做常驻菜单（那是外壳的事，见 `Main.kt`），不做排程
 * （什么时候该提醒归评测层，见 [DesktopReminders]）。
 *
 * **拿不到托盘就静默跳过**，这是这个类的主要职责之一：
 * - 没有桌面环境（`DISPLAY` 为空 / 服务端跑 / CI）⇒ `GraphicsEnvironment.isHeadless()` 为真；
 * - 有 X 但没有托盘宿主（没装 status-notifier / 裸 WM）⇒ `SystemTray.add` 抛 `AWTException`；
 * - 托盘被桌面环境拒收、气泡超出长度……
 *
 * 以上任何一种都只会让 [notify] 返回 `false`，**绝不往外抛**：提醒弹不出去不该把一次正常的
 * 课表刷新变成崩溃点。失败是**粘性**的（[unavailable]）：环境结论不会因为再试一次而改变，
 * 每来一条提醒都先抛一次异常没有意义。
 */
internal object DesktopSystemTray {

    /** 已经判定过「这一端没有系统通知」—— 之后每条都直接短路，不再碰 AWT。 */
    @Volatile
    private var unavailable = false

    /** 托盘图标只装一次（`SystemTray` 里同一个 icon 不能被 add 两次）。 */
    @Volatile
    private var icon: TrayIcon? = null

    /** 这一端现在能不能弹系统通知。无头 / 没有托盘 / 托盘装不上都是 `false`，不会抛。 */
    val canNotify: Boolean
        get() = trayIcon() != null

    /**
     * 弹一条系统通知（托盘气泡）。**真的弹出去了**才返回 `true`。
     *
     * 无头、没有托盘、托盘装不上、气泡被拒 —— 一律 `false` 且不抛。调用方拿到 `false`
     * 应当照旧走它自己那条路（例如消息收纳里那条），别把「没弹出来」当成「没发生」。
     */
    fun notify(title: String, text: String): Boolean {
        val trayIcon = trayIcon() ?: return false
        return try {
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.NONE)
            true
        } catch (e: Throwable) {
            // 连 Error 一起兜：`displayMessage` 在极端情况下会从 AWT 线程外抛 IllegalState；
            // 提醒是**增益**，任何异常都不该外溢到取数那条链上。
            degrade("气泡没弹出去", e)
            false
        }
    }

    /** 只在第一次叫的时候装托盘；之后（含失败之后）都是读缓存。 */
    private fun trayIcon(): TrayIcon? {
        if (unavailable) return null
        icon?.let { return it }
        synchronized(this) {
            if (unavailable) return null
            icon?.let { return it }
            return install()
        }
    }

    private fun install(): TrayIcon? {
        // 无头就是没有桌面环境：`SystemTray.isSupported()` 在无头下也是 false，
        // 但先判这一步能省掉一次异常构造，也把日志说得更准确。
        if (GraphicsEnvironment.isHeadless()) return degrade("没有桌面环境（headless / DISPLAY 为空）", null)
        return try {
            if (!SystemTray.isSupported()) return degrade("这个桌面环境不支持系统托盘", null)
            val trayIcon = TrayIcon(appIcon(), "西交工具箱")
            trayIcon.isImageAutoSize = true
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            Log.d(TAG, "系统托盘就位：系统通知可用")
            trayIcon
        } catch (e: Throwable) {
            // Linux 上最常见的一档：有 X，但状态区没有 status-notifier 宿主 ⇒ AWTException。
            degrade("装不上系统托盘", e)
        }
    }

    /** 记下「这一端没有系统通知」，返回 `null` 让上面几处当短路用。 */
    private fun degrade(reason: String, e: Throwable?): TrayIcon? {
        unavailable = true
        // 装上了但发不出去，就把图标收回去：留一个永远不吭声的托盘图标比没有更怪。
        icon?.let { stale -> runCatching { SystemTray.getSystemTray().remove(stale) } }
        icon = null
        // 一条就够：这是**环境**的结论，不是每条提醒都该刷一遍。
        val detail = e?.let { "（${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""}）" } ?: ""
        Log.d(TAG, "系统通知不可用：$reason$detail")
        return null
    }

    /**
     * 托盘图标：16px 太小，画不下字，所以只画「工具箱」那只 X。
     * 用代码画（不读资源文件）：这一端以前没有任何图标资源，加一张图只会多一个要打包、要校验的文件。
     */
    private fun appIcon(): BufferedImage =
        BufferedImage(ICON_PX, ICON_PX, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(0x1F, 0x6F, 0xD6)
                g.fillRoundRect(0, 0, ICON_PX, ICON_PX, 5, 5)
                g.color = Color.WHITE
                g.stroke = BasicStroke(2f)
                g.drawLine(4, 4, ICON_PX - 4, ICON_PX - 4)
                g.drawLine(ICON_PX - 4, 4, 4, ICON_PX - 4)
            } finally {
                g.dispose()
            }
        }
}
