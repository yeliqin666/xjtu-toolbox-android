package com.xjtu.toolbox.web

import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.social.MatchHost
import com.xjtu.toolbox.social.MatchIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 浏览器这一端的匹配交友**宿主面** —— 如实声明这一端有什么（见 `:core` 的 [MatchHost]）。
 *
 * | 那一件 | 浏览器这一端 |
 * |---|---|
 * | 课表族缓存 | `WebScheduleHost.cache`（`:core` 的键值缝，**wasm 上是 `localStorage`**，前缀 `xjtu-toolbox.`）—— 与「日程」「首页」读的是**同一份**，所以「离线算交集」在这一端是**真算的**：自己的课表 / 考试 / 教材从本端缓存来，对方的码用 `:core` 的算法解出来比 |
 * | 学工系统档案 | **没有** ⇒ `null`（那条链是 `hello.xjtu.edu.cn`，实现只在 `:app`：要 `Context` 与站点会话，`:data` 里一个 hello 字面量都没有 ⇒ 服务端契约也没有 `/api/match/profile`，与桌面端同一条缺口） |
 * | 校园卡消费 | **没有** ⇒ 空表（本端没有校园卡流水的落盘；`:app` 读的是 `CampusCardCache` 那份 SharedPreferences 快照，桌面端也没有） |
 *
 * 后两件如实是「没有」⇒ 屏上那两维（「年级 专业 校区 生源地」与「常在几点吃饭 / 常去哪个食堂」）
 * 是**禁用 + 写明原因**，不是拿空数据凑一维出来；分享码里也不会出现它们（`:core` 的
 * `MatchProfile.build` 本来就要求「开关开着 **且** 有数据」）。**这与桌面端逐条同一个形状**
 *（`desktop/.../DesktopMatch.kt` 的两项也是 null / 空表），所以两端画出来的是同一种界面。
 *
 * ## 扫码那一档
 *
 * `MatchScreen` 的 `scanner` 传 `null`：浏览器里要开相机得走 `getUserMedia`，而它**只在安全上下文
 * （HTTPS / localhost）里存在**，还要处理权限被拒、没有相机这些情形 —— 那是 D12 ③ 那一档
 *（`docs/desktop-port-plan.md` §16.1），不在本批（②：两条编解码缝 + 分享码真能用）的范围里。
 * 传 null 的效果就是**那两颗按钮一个字都不画**、屏上直说只能粘文字码（与桌面同一处置），
 * 而不是画两颗点了没反应的按钮。
 *
 * ## 这一端为什么不再落「说明页」
 *
 * 2026-10-14 接线时 `:web` 落的是 `WebMatchGapScreen`（如实说明页），原因是 `MatchProfile.codecAvailable`
 * 在 wasm 上是 false（deflate 与 URL-safe Base64 两条缝都没有 ⇒ 生成与解析都不可用，而整屏的价值全在
 * 那两步上）。2026-10-15（D12 ②）两条缝补进了 `:core` 的 commonMain（`ZlibCodec` / `UrlSafeBase64`，
 * wasmJs 的 actual 转调它们）⇒ 出码与解码在这一端**真能用**，那张说明页随之删掉。
 */
internal class WebMatchHost : MatchHost {

    /** 与 [WebScheduleHost.cache] **同一个实现**（同一份 `localStorage`、同一个键前缀）。 */
    override fun cache(accountId: String?): ScheduleCacheStore =
        KeyValueScheduleCacheStore(keyValueStore(WebScheduleHost.SCHEDULE_CACHE_STORE))

    override fun identity(): MatchIdentity? = null

    override fun transactions(): List<Transaction> = emptyList()

    /** `:core` 的 commonMain 里没有 `Dispatchers.IO`（wasm 上更没有）⇒ 这一端就落在计算池上。 */
    override val io: CoroutineDispatcher = Dispatchers.Default
}
