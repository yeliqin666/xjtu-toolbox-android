package com.xjtu.toolbox.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * :core 的 [NoticeSource] 在 Android 侧的实现 —— 包住原来的 [NotificationApi]
 * （okhttp + jsoup 爬 29 个站、含反爬挑战）。
 *
 * 这里只是把「阻塞式的抓取」摆到 IO 调度器上（搬迁前是 ViewModel 里 `withContext(Dispatchers.IO)`
 * 做的，位置换了、行为不变）。合并与检索那两条本来就在 API 内部自己切 IO。
 */
class AppNoticeSource(private val api: NotificationApi = NotificationApi()) : NoticeSource {

    override suspend fun page(source: NotificationSource, page: Int): NotificationPage =
        withContext(Dispatchers.IO) { api.getNotificationPage(source, page) }

    override suspend fun merged(sources: List<NotificationSource>, page: Int): MergedNotificationPage =
        api.getMergedNotificationsWithSkipped(sources, page)

    override suspend fun search(sources: List<NotificationSource>, keyword: String): MergedNotificationPage =
        api.search(sources, keyword)
}
