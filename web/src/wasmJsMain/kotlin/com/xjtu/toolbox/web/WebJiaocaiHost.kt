package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.CampusJwxtScheduleApi
import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbook
import com.xjtu.toolbox.jiaocai.JiaocaiTermTextbooks
import com.xjtu.toolbox.jiaocai.toJiaocaiTermTextbooks

/**
 * 「本学期我的教材」那一段在 **serve 形态**下的宿主填空（`:core` 的 [JiaocaiTermTextbooks]）。
 *
 * 数据是**教务**的（`:core` 的 `TextbookItem`），来源是 §5.8 的 `GET /api/jwxt/textbooks`
 * （2026-10-14 批①收尾接上）—— 与日程屏的教材那一栏**同一个端点、同一份解析**。映射（滤掉「无教材」
 * 那些行 + 只搬五格）在 `:core` 的 `toJiaocaiTermTextbooks()`，与 `:app` 的 `AppJiaocaiTermTextbooks`
 * 逐字同一条口径（那边读的是同一份教务数据的落盘缓存）。
 *
 * ## 为什么只传 `term = null`
 * `:app` 那一份先读 `ScheduleCache.readCurrentTerm` 再读那个学期的教材；而服务端这一条**省略 `?term=`
 * 就是当前学期**（与 `/api/jwxt/term` 同一个来源，§5.8）⇒ 本端少问一发，拿到的还是同一份。
 *
 * ## 学号
 * 本端**不传学号**（也编不出）：请求参数里根本没有那一格 —— 学号由服务端从会话里取（§5.2 红线）。
 *
 * ## campus-api 形态
 * 这一屏在非 serve 形态下整屏落 `WebServeOnlyScreen`（见 `ToolboxWebApp` 的 `AppRoute.Jiaocai`
 * 分支），所以这个端口在那形态下根本不会被调用。真被调到（后端不是 serve 而屏还是画了）就是
 * `CampusJwxtScheduleApi.requireServe()` 抛错 —— 与 `CampusJiaocaiApi` 在那一形态下的做法一致，
 * **不返回空表**：空表会把「后端形态不对」说成「本学期真没有教材」，那是假话。
 *
 * @param jwxt §5.8 / §5.9 那一族 serve 取数口（与日程屏共用同一个类的实例语义）
 */
internal class WebJiaocaiTermTextbooks(
    private val jwxt: CampusJwxtScheduleApi,
) : JiaocaiTermTextbooks {

    override suspend fun currentTerm(): List<JiaocaiTermTextbook> =
        jwxt.textbooks(term = null).toJiaocaiTermTextbooks()
}
