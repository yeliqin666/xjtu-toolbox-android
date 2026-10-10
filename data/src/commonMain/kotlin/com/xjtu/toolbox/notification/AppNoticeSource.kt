package com.xjtu.toolbox.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * :core 的 [NoticeSource] 在 android / jvm（桌面）两侧的实现 —— 包住 [NotificationApi]
 * （okhttp + jsoup 爬 29 个站、含反爬挑战）。
 *
 * 这里只是把「阻塞式的抓取」摆到 IO 调度器上（搬迁前是 ViewModel 里 `withContext(Dispatchers.IO)`
 * 做的，位置换了、行为不变）。合并与检索那两条本来就在 API 内部自己切 IO。
 *
 * 29 个源一个都不需要登录（都是公开的公告页），所以它自己没有 `Context` 也不需要任何站点会话 ——
 * `AppRoute.Notification.loginType` 是 null 就是这么个意思（与空闲教室、消息收纳同型）。
 */
class AppNoticeSource(private val api: NotificationApi = NotificationApi()) : NoticeSource {

    override suspend fun page(source: NotificationSource, page: Int): NotificationPage =
        withContext(Dispatchers.IO) { api.getNotificationPage(source, page) }

    override suspend fun merged(sources: List<NotificationSource>, page: Int): MergedNotificationPage =
        api.getMergedNotificationsWithSkipped(sources, page)

    override suspend fun search(sources: List<NotificationSource>, keyword: String): MergedNotificationPage =
        api.search(sources, keyword)
}
