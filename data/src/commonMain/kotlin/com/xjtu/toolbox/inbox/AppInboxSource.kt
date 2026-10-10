package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite

/**
 * `:core` 的 [InboxSource] 的实现（Android 与桌面侧共用）—— 包住 [SchoolInbox]。
 *
 * 学校那四路（消息 / 事务中心 / 预约 / 校车）走一网通办站点，取数一行未改；会话由**本类自己
 * ensure**（`AppRoute.Inbox.loginType` 是 null：这一屏要登哪个站点是数据源的事，与
 * `AppEmptyRoomSource`/`GraduateJudgeSource` 同型）—— 与搬进 `:data` 之前 `:app` 里那两句逐字相同。
 *
 * ## `Context` 收成构造参数 [requeryLibraryBooking]
 *
 * [afterRefresh] 那一段（「有座位待办就现查一次图书馆，签过到的马上消失」）原来直接用
 * `loginState` 与 `Context`：现查一次要 `LibraryApi` + 一个 `SiteSession`→`LibrarySession`
 * 的适配器，并发出去要 `LibraryStatus.publish(context, …)`（后台提醒调度器 + 首页信号 +
 * 收纳待办，全在 `:app`，还绑着 `android.content`）。`Context` 只喂给最后那一件事，
 * 所以缝就照这一件事切：本端把「现查 + 发出」整段做成一个 `suspend` 闭包传进来
 * （`:app` 那一份见它的 `appInboxLibraryBooking`），桌面端传 `null`。
 *
 * `null` 的语义不是「降级」而是**与「这里永远早返回」等价**：这一屏的座位待办只有
 * `:app` 的 `LibraryStatus` 会写（桌面端没有任何东西写 `LIBRARY` 那一类），所以
 * [afterRefresh] 在桌面端本来就在第一句早返回 —— 与 `AppCampusCardSource` 的
 * `store = null`（「缓存里什么都没有」）同一条规矩。
 */
class AppInboxSource(
    private val sessionManager: SessionManager?,
    /** 本端独有的图书馆座位补拉；`null` = 本端没有那一路（桌面端）。见类 KDoc。 */
    private val requeryLibraryBooking: (suspend () -> Unit)? = null,
) : InboxSource {

    override suspend fun refresh(account: String?) {
        val manager = sessionManager ?: return
        SchoolInbox.refresh(manager.ensureSite(LoginType.YWTB, userInitiated = true), account)
    }

    override fun isDue(account: String?, now: Long): Boolean = SchoolInbox.isDue(account, now)

    override suspend fun afterRefresh(data: InboxData) {
        // 有座位待办时顺带现查一次，签过到的马上消失
        if (data.todos[InboxCategories.LIBRARY].isNullOrEmpty()) return
        requeryLibraryBooking?.invoke()
    }
}
