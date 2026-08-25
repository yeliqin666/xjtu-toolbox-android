package com.xjtu.toolbox.notification

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.home.HomeStats
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
            NoticeWidgetUpdater.publishTitles(app, emptyList())
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
        for (source in selected) {
            val page = withContext(Dispatchers.IO) {
                runCatching { api.getNotificationPage(source, 1) }
                    .onFailure { Log.w(TAG, "source ${source.displayName}: ${it.message}") }
                    .getOrNull()
            }
            if (page != null) collected += page.items
        }

        val items = collected
            .filter { it.link.isNotBlank() }
            .sortedByDescending { it.date }

        val seen = NoticeWatchStore.seenLinks(app).toMutableList()
        val seenSet = seen.toHashSet()
        val baselined = NoticeWatchStore.baselinedSources(app).toMutableSet()

        // 新勾上的来源先记基线，不把人家栏目里本来就有的旧公告当成「新通知」。
        for (source in selected) {
            if (source.name in baselined) continue
            items.filter { it.source == source }.forEach { n ->
                if (seenSet.add(n.link)) seen.add(n.link)
            }
            baselined.add(source.name)
        }

        val fresh = items.filter { it.link !in seenSet }
        fresh.forEach { n ->
            if (seenSet.add(n.link)) seen.add(n.link)
        }

        NoticeWatchStore.setSeenLinks(app, seen)
        NoticeWatchStore.setBaselinedSources(app, baselined)
        NoticeWatchStore.setLastSyncAt(app, now)

        val multi = selected.size > 1
        val titles = items.take(3).map { formatTitle(it, multi) }
        NoticeWatchStore.setLastTitles(app, titles)
        NoticeWidgetUpdater.publishTitles(app, titles)

        val newest = items.firstOrNull()?.title
        // 关键词只筛"推哪些"，不筛"抓哪些"、也不筛"记哪些已读"——上面 fresh 已经
        // 全部写进 seenLinks 了。于是它不增加任何请求，纯粹减少打扰；关掉过滤时
        // 也不会有一堆积压的旧公告突然涌出来。
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
            if (posted) NoticeNotifier.notifyNew(app, toPush)
        }
        if (!newest.isNullOrBlank()) {
            // 首页/气泡的"未读"标记跟推送同口径：被关键词滤掉的公告不该在别处再冒一次。
            HomeStats.putLatestNoticeTitle(app, newest, markUnseen = notify && toPush.isNotEmpty() && !posted)
        }

        Result(titles, newest, fresh.size, usedCache = false)
    }

    private fun formatTitle(item: Notification, multi: Boolean): String =
        if (multi) "${item.source.displayName} · ${item.title}" else item.title
}
