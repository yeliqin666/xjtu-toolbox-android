package com.xjtu.toolbox.dormpower

/**
 * 宿舍电费（`ssn.xjtu.edu.cn/cems`）的模型。
 *
 * 从 `:app` 的 `dormpower/DormPowerStore.kt` / `DormPowerModels.kt` 里把**模型**剪出来搬进
 * `:core`（同包、同类名）：屏幕与 Store 分别是两端共用的，模型得跟着它们走；而解析
 * （`DormPowerParsers`，读字节、按 GBK 兜底 —— 用 `java.nio`）与取数（`DormPowerApi`，okhttp）
 * 留在 `:data`，`jsonObject`/`jsonPrimitive` 那些解析依赖也不进 commonMain。
 */

/** 绑定了的宿舍；[name] 是「园区/楼/单元/层/房间…」用 / 连起来的全名。 */
data class DormRoom(val id: String, val name: String)

/** 一间宿舍最近一次查到的电量；[kwh] 为 null 表示系统查不到。 */
data class DormReading(val room: DormRoom, val kwh: Double?, val at: Long)

/** 电量低于这个数就提醒（度）。 */
const val LOW_KWH = 10.0

/**
 * 宿舍电费的**取数端口**：屏与 Store 都在 `:core`，取数由 `:data` 填。
 *
 * | 端 | 实现 |
 * |---|---|
 * | Android / 桌面 | `:data` 的 [DormPowerApi]（包住 okhttp + `SsnSession` 会话） |
 *
 * 三个方法就是搬迁前各调用点真正用到的那三件事，名称与参数逐字相同 —— 所以
 * 「查一遍所有绑定的宿舍、写回本机」的编排（[DormPowerStore.refresh]）与屏上那句
 * 「换一张能直接登录缴费页的入口地址」在两端是同一条代码路径。
 *
 * ## 为什么 IO 调度不在端口上
 *
 * `:data` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`；Web 端还没有这条
 * 路由，将来若走挂起接口也不需要。与 `FitnessSource` / `CouponSource` 同一条约定。
 *
 * ## 会话失效
 *
 * 三个方法都可能抛 `SessionExpiredFailure`（`:app`/`:data` 的 `AuthExpiredException` 是它的
 * 实现）—— 屏据此回登录/退回，Store 的 [DormPowerStore.refresh] 则按房间吞掉（见那里的 KDoc）。
 */
interface DormPowerSource {

    /**
     * 缴费页入口地址（给浏览器用）。缴费站没有可搬运的会话 cookie，每次进页面都要凭 OAuth
     * 回调里一次性的 code 换会话 ⇒ 实现方每次重新走一遍 OAuth、拦下带 code 的那一跳，
     * 把地址交出来（`:app` 搬过来时的那条 `SsnSession.freshPayUrl` 一字未改）。
     *
     * 实现方顺手记下「用户进过这一页」（[DormPowerStore.markUsed]）—— 搬迁前这一句写在屏上，
     * 就在拿到地址之后，成功才记，本方法保持同一个时机。
     */
    suspend fun freshPayUrl(): String

    /** 已绑定的宿舍列表（`cid` 由实现从会话里取）。 */
    suspend fun rooms(): List<DormRoom>

    /** 某间宿舍的剩余电量（度）；系统没有这个房间的表时返回 null。 */
    suspend fun kwh(roomId: String): Double?
}
