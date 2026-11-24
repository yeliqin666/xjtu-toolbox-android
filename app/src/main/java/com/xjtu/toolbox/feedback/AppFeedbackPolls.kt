package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.bulletin.BulletinStore
import kotlin.time.Clock

/** 反馈页投票区的 Android 实现：投票来自公告缓存，票写进飞书，和首页的公告投票是同一份。 */
object AppFeedbackPolls : FeedbackPolls {
    private val store by lazy { BulletinStore() }

    /** 进行中的（按版本、时间窗筛过），加往期最近 [HISTORY] 条。 */
    private fun select(all: List<Bulletin>): List<Bulletin> {
        val now = Clock.System.now()
        val polls = all.filter { it.isPoll && it.active }
        val open = polls.filter {
            !it.isClosedAt(now) && BulletinRules.isActive(it, now, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }.sortedByDescending { it.id }
        val closed = polls.filter { it.isClosedAt(now) }.sortedByDescending { it.endsAt }.take(HISTORY)
        return open + closed
    }

    private const val HISTORY = 10

    override fun cached(): List<Bulletin> = select(store.peekCached())

    override suspend fun refresh(): List<Bulletin>? {
        val fetched = runCatching { BulletinApi.fetch(BuildConfig.VERSION_NAME) }.getOrNull() ?: return null
        store.cachedJson = fetched.rawJson
        return select(fetched.items)
    }

    override fun myVote(id: String): List<String>? = store.myVote(id)

    override suspend fun vote(poll: Bulletin, selected: List<String>) {
        FeedbackApi.submit(
            ticket = FeedbackStore.newTicket(),
            category = "投票·${poll.id}",
            content = selected.joinToString("、"),
            contact = "",
            anonId = FeedbackStore.anonId(),
        )
        store.recordVote(poll.id, selected)
    }
}
