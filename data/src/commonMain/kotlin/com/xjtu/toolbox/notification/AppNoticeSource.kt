package com.xjtu.toolbox.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** :core 的 [NoticeSource] 在 android / jvm（桌面）两侧的实现。 */
class AppNoticeSource(private val api: NotificationApi = NotificationApi()) : NoticeSource {

    override suspend fun page(source: NotificationSource, page: Int): NotificationPage =
        withContext(Dispatchers.IO) { api.getNotificationPage(source, page) }

    override suspend fun merged(sources: List<NotificationSource>, page: Int): MergedNotificationPage =
        api.getMergedNotificationsWithSkipped(sources, page)

    override suspend fun search(sources: List<NotificationSource>, keyword: String): MergedNotificationPage =
        api.search(sources, keyword)
}
