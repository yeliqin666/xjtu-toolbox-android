package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.bulletin.BulletinStore
import com.xjtu.toolbox.bulletin.PollResult
import kotlin.time.Clock

/** 反馈页投票区的 Android 实现：投票来自公告缓存，票写进 Cloudflare D1 并实时汇总结果。 */
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
    private const val OTHER_OPTION = "其他"

    override fun cached(): List<Bulletin> = select(store.peekCached())

    override suspend fun refresh(): List<Bulletin>? {
        val fetched = runCatching { BulletinApi.fetch(BuildConfig.VERSION_NAME) }.getOrNull() ?: return null
        store.cachedJson = fetched.rawJson
        val selected = select(fetched.items)
        val ids = selected.map { it.id }
        val live = if (FeedbackApi.isConfigured && ids.isNotEmpty()) {
            runCatching { FeedbackApi.pollResults(ids) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return overlayLiveResults(selected, live)
    }

    internal fun overlayLiveResults(
        polls: List<Bulletin>,
        live: Map<String, FeedbackApi.PollLiveSummary>,
    ): List<Bulletin> {
        if (live.isEmpty()) return polls
        return polls.map { poll ->
            val summary = live[poll.id] ?: return@map poll
            // 实时结果有人投票（或公告里还没有写回静态结果）时用实时统计覆盖
            if (summary.voters == 0 && poll.results.isNotEmpty()) return@map poll
            val known = poll.options.toSet()
            val otherVotes = summary.counts.entries
                .filter { (opt, _) -> opt !in known }
                .sumOf { it.value }
            val merged = poll.options.map { opt -> PollResult(opt, summary.counts[opt] ?: 0) } +
                if (poll.allowOther || otherVotes > 0) listOf(PollResult(OTHER_OPTION, otherVotes)) else emptyList()
            poll.copy(
                results = merged,
                voters = summary.voters,
            )
        }
    }

    override fun myVote(id: String): List<String>? = store.myVote(id)

    override suspend fun vote(poll: Bulletin, selected: List<String>) {
        FeedbackApi.vote(
            pollId = poll.id,
            selected = selected,
            anonId = FeedbackStore.anonId(),
        )
        store.recordVote(poll.id, selected)
    }
}
