package com.xjtu.toolbox.shell

/** 冷启动同一刻只弹一层（miuix 的 WindowBottomSheet 叠两层会互相顶掉），[layer] 挑优先级最高的那一个。 */
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
