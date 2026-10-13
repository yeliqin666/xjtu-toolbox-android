package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.social.MatchHost
import com.xjtu.toolbox.social.MatchIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 桌面端的匹配交友**宿主面** —— 如实声明这一端有什么（见 `:core` 的 [MatchHost]）。
 *
 * | 那一件 | 桌面这一端 |
 * |---|---|
 * | 课表族缓存 | `DesktopScheduleHost.cache`（0600 的键值文件 `schedule_cache<账号后缀>`）—— 与「我的课表」「首页」读的是**同一份**，所以「离线算交集」这一件事在这一端是**真算的** |
 * | 学工系统档案 | **没有** ⇒ `null`（`HelloProfileStore` 是 `:app` 的实现：要 hello 站点的会话，头像还是 Bitmap） |
 * | 校园卡消费 | **没有** ⇒ 空表（`:app` 读的是 `CampusCardCache` 那份 SharedPreferences 快照；桌面连校园卡屏都不落盘 —— `CampusCardStore` 在这一端传的是 `null`） |
 *
 * 后两件如实是「没有」⇒ 屏上那两维（「年级 专业 校区 生源地」与「常在几点吃饭 / 常去哪个食堂」）
 * 是**禁用 + 写明原因**，不是拿空数据凑一维出来。分享码里也不会出现它们（`:core` 的
 * `MatchProfile.build` 本来就要求「开关开着 **且** 有数据」）。
 *
 * ## TODO（要真点亮那两维，各自缺的东西）
 *
 * 1. **学工档案**：把 `hello` 站点（`hello.xjtu.edu.cn`，CAS 回跳）的取数接进 `:data`、档案模型接进
 *    `:data`/`:core`（与 `:app` 同一套字段），头像那一格留给端上画（`:core` 已有图片缝）。
 *    那时 [identity] 照着填即可，屏上那一维就会亮；
 * 2. **校园卡消费**：桌面**已经有**校园卡那条路由（`AppRoute.CampusCard`），缺的只是**落盘** ——
 *    给它接一份键值落盘（键名与形状照 `:app` 的 `CampusCardCache`），那份快照就能在 [transactions] 里读出来。
 *
 * 扫码（相机 / 相册）**不在这一格**：见 `ToolboxDesktopApp` 的 Match 分支 —— 这一端没有相机，
 * `MatchScreen` 的 `scanner` 传 `null`，那两颗按钮一个字都不画，屏上直说只能粘文字码。
 *
 * @param auth 会话 + 凭据（只是转给 [DesktopScheduleHost] —— 课表缓存的落盘文件按账号后缀分）
 */
internal class DesktopMatchHost(private val auth: DesktopAuth) : MatchHost {

    /** 与 `DesktopScheduleHost.cache` **同一个实现**（同一份键值文件、同一个账号后缀）。 */
    override fun cache(accountId: String?): ScheduleCacheStore =
        DesktopScheduleHost(auth).cache(accountId)

    override fun identity(): MatchIdentity? = null

    override fun transactions(): List<Transaction> = emptyList()

    /** 与 `DesktopScheduleHost.io` 一样：这一端的本地读写就是文件 IO。 */
    override val io: CoroutineDispatcher = Dispatchers.IO
}
