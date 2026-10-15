package com.xjtu.toolbox.dormpower

/** 宿舍电费（`ssn.xjtu.edu.cn/cems`）的模型。 */

/** 绑定了的宿舍；[name] 是「园区/楼/单元/层/房间…」用 / 连起来的全名。 */
data class DormRoom(val id: String, val name: String)

/** 一间宿舍最近一次查到的电量；[kwh] 为 null 表示系统查不到。 */
data class DormReading(val room: DormRoom, val kwh: Double?, val at: Long)

/** 电量低于这个数就提醒（度）。 */
const val LOW_KWH = 10.0

/** 宿舍电费的取数端口：屏与 Store 都在 `:core`，取数由 `:data` 填。 */
interface DormPowerSource {

    /** 缴费页入口地址（给浏览器用）。 */
    suspend fun freshPayUrl(): String

    /** 已绑定的宿舍列表（`cid` 由实现从会话里取）。 */
    suspend fun rooms(): List<DormRoom>

    /** 某间宿舍的剩余电量（度）；系统没有这个房间的表时返回 null。 */
    suspend fun kwh(roomId: String): Double?
}
