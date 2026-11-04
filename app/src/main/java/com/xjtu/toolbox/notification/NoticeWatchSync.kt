package com.xjtu.toolbox.notification

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.widget.NoticeWidgetStore
import com.xjtu.toolbox.widget.NoticeWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 按用户勾选的来源抓一页通知，写小组件，必要时弹系统通知。
 *
 * 首页刷新和 WorkManager 走同一条路，避免两套去重。
 * 抓取串行：通知站不需要登录，但并发二十几个域名既费电也容易被站点当爬虫。
 */
internal object NoticeWatchSync {
    private const val TAG = "NoticeWatch"
    const val TTL_MS = 4L * 60 * 60 * 1000L

    private val lock = Mutex()

    data class Result(
        val titles: List<String>,
        val newestTitle: String?,
        val newCount: Int,
        val usedCache: Boolean,
    )

    suspend fun sync(
        context: Context,
        notify: Boolean,
        force: Boolean = false,
    ): Result = lock.withLock {
        val app = context.applicationContext
        val selected = NoticeWatchStore.sources(app)
        if (selected.isEmpty()) {
            NoticeWidgetUpdater.publish(app, emptyList())
            NoticeWatchStore.setLastTitles(app, emptyList())
            return@withLock Result(emptyList(), null, 0, usedCache = false)
        }

        val now = System.currentTimeMillis()
        val last = NoticeWatchStore.lastSyncAt(app)
        val cachedTitles = NoticeWatchStore.lastTitles(app)
        if (!force && last > 0L && now - last < TTL_MS && cachedTitles.isNotEmpty()) {
            return@withLock Result(
                titles = cachedTitles,
                newestTitle = cachedTitles.firstOrNull(),
                newCount = 0,
                usedCache = true,
            )
        }

        val api = NotificationApi()
        val collected = mutableListOf<Notification>()
        val successfulSources = mutableSetOf<NotificationSource>()
        for (source in selected) {
            val page = withContext(Dispatchers.IO) {
                runCatching { api.getNotificationPage(source, 1) }
                    .onFailure { Log.w(TAG, "source ${source.displayName}: ${it.message}") }
                    .getOrNull()
            }
            if (page != null) {
                collected += page.items
                successfulSources += source
            }
        }

        if (successfulSources.isEmpty()) {
            return@withLock Result(cachedTitles, cachedTitles.firstOrNull(), 0, usedCache = true)
        }
        val items = collected
            .filter { it.link.isNotBlank() }
            .sortedByDescending { it.date }

        val delta = noticeDelta(
            items, successfulSources, NoticeWatchStore.seenLinks(app),
            NoticeWatchStore.baselinedSources(app), NoticeWatchStore.latestDates(app),
            com.xjtu.toolbox.util.todayOnCampus(),
        )
        val fresh = delta.fresh
        com.xjtu.toolbox.inbox.InboxStore.post(fresh.map(com.xjtu.toolbox.inbox.OwnInbox::notice))
        NoticeWatchStore.setSeenLinks(app, delta.seen)
        NoticeWatchStore.setBaselinedSources(app, delta.baselined)
        NoticeWatchStore.setLatestDates(app, delta.latestDates)
        NoticeWatchStore.setLastSyncAt(app, now)

        val multi = selected.size > 1
        val titles = items.take(3).map { formatTitle(it, multi) }
        NoticeWatchStore.setLastTitles(app, titles)
        NoticeWidgetUpdater.publish(
            app,
            items.take(NoticeWidgetStore.MAX_ENTRIES).map { NoticeWidgetStore.Entry(formatTitle(it, multi), it.date.toEpochDays(), it.link.takeIf { l -> l.startsWith("http") }) },
        )

        val newest = items.firstOrNull()?.title
        val keywords = NoticeWatchStore.keywords(app)
        val toPush = if (keywords.isEmpty()) fresh else fresh.filter { n ->
            keywords.any { k -> n.title.contains(k, ignoreCase = true) }
        }
        if (keywords.isNotEmpty()) {
            Log.d(TAG, "关键词过滤：${fresh.size} 条新公告 -> 推 ${toPush.size} 条")
        }
        var posted = false
        if (notify && toPush.isNotEmpty() && NoticeWatchStore.isEnabled(app)) {
            posted = NoticeNotifier.canPost(app)
            if (posted) {
                val tags = NoticeAiTagger.tag(app, toPush.take(NoticeNotifier.MAX_SHOWN))
                NoticeNotifier.notifyNew(app, toPush, tags)
            }
        }
        val bubble = toPush.firstOrNull()
        if (bubble != null && notify && !posted) {
            HomeStats.putLatestNoticeTitle(
                app,
                bubble.title,
                markUnseen = true,
                unseenLink = bubble.link,
            )
        } else if (!newest.isNullOrBlank()) {
            HomeStats.putLatestNoticeTitle(app, newest, markUnseen = false)
        }

        Result(titles, newest, fresh.size, usedCache = false)
    }

    private fun formatTitle(item: Notification, multi: Boolean): String =
        if (multi) "${item.source.displayName} · ${item.title}" else item.title
}
