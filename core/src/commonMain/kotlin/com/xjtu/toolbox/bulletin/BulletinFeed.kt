package com.xjtu.toolbox.bulletin

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 公告的那**一份可用清单 + 关掉它的语义** —— 从 `:app` 的 `main/LaunchNotices.kt` 搬进 `:core`。
 *
 * ## 为什么这一段在 `:core`
 *
 * 公告这条链上，模型与解析规则（[Bulletin] / [BulletinRules]）早就在共享层、本地状态
 * （[BulletinStore]，键值缝）与取数（`:data` 的 `BulletinApi`）也已在共享层，两个**宿主面**
 * （首页顶部那条堆叠条 `BulletinNoticePanel`、冷启动那条弹框 `BulletinLaunchDialog`）这一轮也
 * 搬了过来。剩下的最后一截是宿主们各自要照着办的两条判定，原来只写在 `:app` 的 `LaunchNotices` 里：
 *
 *  1. **[visible]：现在哪些公告算数** —— 把本地状态（关过的 / 确认过的 / 进程内推迟的）与
 *     「宿主额外合成的那一条」（例如「发现新版本」）一起喂给 [BulletinRules.visible]。
 *     各端各抄一遍的结果只会是「同一台机器上，首页那条和弹框那条不是同一批公告」。
 *  2. **[dismiss] / [dismissDialog]：关掉一条意味着什么** —— 等级决定记账方式（见下）。
 *     分档表抄错一档，用户点掉的「必须升级」就会永久消失。
 *
 * ## 端上各留什么
 *
 * 取数（`BulletinApi.fetch`，UA 里的版本号由宿主递）、「合成的那条更新提示」怎么记已见
 *（`:app` 是 `CredentialStore.markUpdateNoticeSeen`）、网络失败退本地缓存、以及两处画法
 *（条与弹框）都留在宿主。这个对象**没有状态**：状态全在 [BulletinStore] 里（与搬迁前同一份）。
 */
object BulletinFeed {

    /**
     * 此刻该显示的公告，按等级排好序。
     *
     * [cached] 是宿主读出来的那份（`:app`/桌面都是 `BulletinStore.peekCached()`，取数成功时换成
     * 刚落盘的那一份）；[extras] 是宿主自己合成的、不在远端配置里的条目（例如
     * `BulletinRules.syntheticUpdate`）。其余判据与 [BulletinRules.visible] 逐字相同。
     *
     * [currentVersionCode] 为 null = 这一端没有内部版本号（桌面就是这种情况）⇒ 只按 [Bulletin.targetVersion]
     */
    fun visible(
        store: BulletinStore,
        cached: List<Bulletin>,
        extras: List<Bulletin> = emptyList(),
        currentVersion: String,
        currentVersionCode: Int? = null,
        now: Instant = Clock.System.now(),
    ): List<Bulletin> = BulletinRules.visible(
        items = cached + extras,
        now = now,
        currentVersion = currentVersion,
        currentVersionCode = currentVersionCode,
        dismissedIds = store.dismissedIds,
        ackedIds = store.ackedIds,
        snoozedIds = BulletinStore.sessionSnoozedIds(),
    )

    /**
     * 点公告条上那个 ✕ / 关掉一条已经看过的公告：**等级决定怎么记账**。
     *
     *  - `CRITICAL` → [BulletinStore.ack]：确认过就不再弹（与「关了」分开记，见 [BulletinRules.isHidden]）；
     *  - `FORCE_UPDATE` → [BulletinStore.snooze]：只记在**进程内**，下次冷启动照旧出现
     *    —— 「必须升级」不该被一个 ✕ 永久消掉；
     *  - 其余（含投票）→ [BulletinStore.dismiss]。
     *
     * 返回 `false` = **这一条不归这里记账**：宿主自己合成的那条（[Bulletin.synthesized]，例如
     * 「发现新版本」）的「已见」记在宿主的更新提示账本上，由调用方处理（`:app` 是
     * `CredentialStore.markUpdateNoticeSeen`）。
     */
    fun dismiss(store: BulletinStore, bulletin: Bulletin): Boolean {
        if (bulletin.synthesized) return false
        when (bulletin.level) {
            BulletinLevel.CRITICAL -> store.ack(bulletin.id)
            BulletinLevel.FORCE_UPDATE -> store.snooze(bulletin.id)
            else -> store.dismiss(bulletin.id)
        }
        return true
    }

    /**
     * 冷启动那个弹框被关掉（点「稍后」或点外面）。
     *
     * 只有「必须升级」那一条要记账：推迟到下次冷启动。其余等级**不记** —— 它已经在公告条上
     * 露过面，用户想关的时候要关的是那条，不是这个弹框。
     */
    fun dismissDialog(store: BulletinStore, bulletin: Bulletin) {
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) store.snooze(bulletin.id)
    }
}
