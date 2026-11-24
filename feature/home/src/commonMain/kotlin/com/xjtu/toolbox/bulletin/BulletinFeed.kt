package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.inbox.InboxStore
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

    /** 冷启动那个弹框被关掉（点「稍后」或点外面）：不算看过，下次冷启动照弹。 */
    fun dismissDialog(store: BulletinStore, bulletin: Bulletin) {
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) store.snooze(bulletin.id)
    }

    /**
     * 弹框里点了「知道了」：和点公告条上的 ✕ 一样记账（重要 ⇒ 已确认，其余 ⇒ 已关闭）。
     * 「必须升级」点的是「立即更新」，不在这里记账。
     */
    fun confirmDialog(store: BulletinStore, bulletin: Bulletin) {
        if (bulletin.level != BulletinLevel.FORCE_UPDATE) dismiss(store, bulletin)
    }

    /**
     * 消息收纳里清除掉的公告，按首页 ✕ 同一套记账，首页和冷启动弹窗就不再出现。返回是否记了新账。
     * [bulletins] 要先按版本升级过（[BulletinRules.resolveForVersion]）。「必须升级」只推迟本次启动，
     * 冷启动时传 [includeForced] = false：上次划掉的那条不能把这次的提醒也吞掉（见 [restoreForcedInInbox]）。
     */
    fun absorbInboxClears(store: BulletinStore, bulletins: List<Bulletin>, includeForced: Boolean): Boolean {
        val cleared = InboxStore.load().cleared
        if (cleared.isEmpty()) return false
        val handled = store.dismissedIds + store.ackedIds + BulletinStore.sessionSnoozedIds()
        val fresh = bulletins.filter {
            it.inboxId in cleared && it.id !in handled && !it.isPoll && !it.synthesized &&
                (includeForced || it.level != BulletinLevel.FORCE_UPDATE)
        }
        fresh.forEach { dismiss(store, it) }
        return fresh.isNotEmpty()
    }

    /** 冷启动时把上次在收纳里划掉的「必须升级」放回收纳：它只能推迟，不能永久清掉（已读状态保留）。 */
    fun restoreForcedInInbox(bulletins: List<Bulletin>) {
        val data = InboxStore.load()
        val ids = bulletins.filter { it.level == BulletinLevel.FORCE_UPDATE && it.inboxId in data.cleared }.map { it.inboxId }
        if (ids.isNotEmpty()) InboxStore.unclearMessages(ids, data.readAt.filterKeys { it in ids })
    }
}
