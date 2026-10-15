package com.xjtu.toolbox.bulletin

import kotlin.time.Clock
import kotlin.time.Instant

/** 公告的可用清单 + 关掉它的语义。 */
object BulletinFeed {

    /** 此刻该显示的公告，按等级排好序。 */
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

    /** 点公告条上那个 ✕ / 关掉一条已经看过的公告：等级决定怎么记账。 */
    fun dismiss(store: BulletinStore, bulletin: Bulletin): Boolean {
        if (bulletin.synthesized) return false
        when (bulletin.level) {
            BulletinLevel.CRITICAL -> store.ack(bulletin.id)
            BulletinLevel.FORCE_UPDATE -> store.snooze(bulletin.id)
            else -> store.dismiss(bulletin.id)
        }
        return true
    }

    /** 冷启动那个弹框被关掉（点「稍后」或点外面）。 */
    fun dismissDialog(store: BulletinStore, bulletin: Bulletin) {
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) store.snooze(bulletin.id)
    }
}
