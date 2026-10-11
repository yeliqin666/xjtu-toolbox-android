package com.xjtu.toolbox.iclassface

import kotlinx.datetime.LocalDate

/**
 * 一条签到/刷卡记录。
 *
 * 与搬迁前 `IclassfaceApi.CheckinRecord` 的五个字段逐字相同（原来是嵌套在 API 类里的 data class，
 * 现在住在共享层 —— 它是屏要画的东西，而 API 类在 `:data`）。字段名与类型都没改，屏上的用法
 * （`record.time` / `record.location` / `record.type`）一行未动，只有类型名从
 * `IclassfaceApi.CheckinRecord` 变成顶层 `CheckinRecord`。
 */
data class CheckinRecord(
    val name: String,
    val studentNo: String,
    val time: String,       // "2026-06-04 14:14:37"
    val location: String,   // "中3-2306"
    val type: String        // "电子班牌刷卡" / "电子班牌人脸识别"
)

/**
 * 人脸识别签到（`iclassface.xjtu.edu.cn`）的**取数端口**。
 *
 * 方法就是搬迁前 `IclassfaceApi`（`:app`，okhttp 抓 iclassface 的服务端渲染表格）里那一个动作的
 * 形状 —— 名称、参数、返回类型与搬迁前一致。
 *
 * | 端 | 实现 | 备注 |
 * |---|---|---|
 * | Android / 桌面 | `:data` 的 `AppIclassfaceSource`（包住 `IclassfaceApi`） | 走自己的站点会话（`iclassface`） |
 * | Web | 还没有 | 屏搬好了，取数要等 campus-api 或 `:server` 的 `/api` 端点给出（如实记在 `:web` 的未搬清单里） |
 *
 * ## 日期为什么收 `kotlinx.datetime.LocalDate`
 *
 * 搬迁前屏上用 `java.time.LocalDate`（`commonMain` 没有它），而 `:core` 的既有做法是
 * `kotlinx.datetime`（考勤屏同一处：日期选择器 `AppDatePickerDialog` 收也吐这个类型）。
 * `toString()` 两边都是 ISO `yyyy-MM-dd`，所以打给上游的 `date=` 参数一个字没变。
 *
 * ## IO 调度
 *
 * `:data` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`（搬迁前是屏里
 * `withContext(Dispatchers.IO)`，同一层同一个调度器）—— 与 `AttendanceSource` / `CouponSource`
 * 同一条约定。
 *
 * ## 会话失效
 *
 * 可能抛 `SessionExpiredFailure`（`:app`/`:data` 的 `AuthExpiredException` / `SessionExpiredException`
 * 都是它的实现）—— 屏据此走 `:core` 的 `LocalAuthExpiry`（与搬迁前 `handleAuthExpired` 同义）。
 */
interface IclassfaceSource {

    /**
     * 查询指定日期的签到记录。
     * 返回空列表表示当天确实没有任何记录（含"还没刷上卡"的情况）。
     */
    suspend fun fetchRecords(date: LocalDate): List<CheckinRecord>
}
