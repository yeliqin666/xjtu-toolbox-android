package com.xjtu.toolbox.shell

/**
 * 冷启动同一刻只弹一层（miuix 的 WindowBottomSheet 叠两层会互相顶掉），[layer] 挑优先级最高的那一个。
 *
 * 这一段原本是 `:app` 的 `main/LaunchNotices.kt` 里 `LaunchNoticeDialogs` 那个 `when` ——
 * 公告 / 新版本 / 更新日志三档的**先后**是共享的（桌面也按同一条口径只弹一层），
 * 每一档画什么由端上注入（`:app` 是公告弹框、APK 更新弹框、本地更新日志；
 * 桌面只有公告那一档，它的更新是 `.deb` 那一套，见 `DesktopUpdate`）。
 *
 * 参数是「这一档现在该不该弹」：`when` 的顺序就是优先级，与搬迁前逐字一致。
 */
enum class ShellLaunchLayer {

    /** 公告弹框（`:app` 的 `BulletinLaunchDialog`）。 */
    BULLETIN,

    /** 自动检查到的新版本（`:app` 的 `AutoUpdateDialog`）。 */
    UPDATE,

    /** 本地更新日志（`:app` 的 `UpdateNoticeDialog`）。 */
    CHANGELOG,
    ;

    companion object {

        /** 挑这一刻该弹的那一层；null = 一层都不弹。 */
        fun layer(bulletin: Boolean, update: Boolean, changelog: Boolean): ShellLaunchLayer? = when {
            bulletin -> BULLETIN
            update -> UPDATE
            changelog -> CHANGELOG
            else -> null
        }
    }
}
