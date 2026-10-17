package com.xjtu.toolbox.venue

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable

/** 体育场馆的数据模型。 */

/** 场馆（从 product/index.html 解析） */
data class Venue(
    val id: Int,
    val name: String,
    val address: String? = null,
    val iconType: String? = null,   // icon-badminton, icon-tennis, ...
    /** 可提前几天预订 */
    val advanceDay: Int = 7,
    /** 一次最多订几个时段 */
    val advanceNum: Int = 8,
)

/** 一个时段下的一个可选场地单元（从 findtime.html + seat/seat.html 合并得出） */
data class AreaSlot(
    val areaDetailId: Long,   // 场地明细ID，提交订单 stockdetailids 用；无细分场地时退化为 stockId
    val areaName: String,     // "场地1"/"场地2"/...；无细分场地时为 "预订"；已满时为 "已满"
    val stockId: Long,        // 库存ID，提交订单 stock map 的 key，同一时段下所有场地共享
    val timeSlot: String,     // 18:00-19:00
    val price: Double,
    val date: String,         // 2026-03-03
    val allCount: Int,        // 该时段总容量（时段级，非逐场地）
    val usingNum: Int,        // 已用（时段级）
    val surplus: Int,         // 剩余（时段级）——服务端只在这个粒度给出占用数据
    val serviceid: String
) {
    val isAvailable: Boolean get() = surplus > 0
}

/** 验证码数据 */
data class CaptchaData(
    val id: String,
    val backgroundImage: String,  // data:image/jpeg;base64,...
    val sliderImage: String,      // data:image/png;base64,...
    val bgWidth: Int,
    val bgHeight: Int,
    val sliderWidth: Int,
    val sliderHeight: Int
)

/** 服务端在 order/show.html 步骤生成的待提交订单参数（必须原样带回，不能自拼）。 */
data class PendingOrder(val rawParamJson: String)

/** 预订结果 */
data class BookingResult(
    val success: Boolean,
    val orderId: String? = null,
    val price: Double = 0.0,
    val message: String = ""
)

/** 一个订单明细（一个日期/时段/场地）。 */
data class OrderDetail(
    val date: String,
    val timeSlot: String,
    val areaName: String,
    val price: Double,
    val serviceId: String,
    val serviceName: String
)

/** 订单信息。状态值与场馆服务端保持一致：0 预订中、1 预订成功、2 预订取消。 */
data class OrderInfo(
    val orderId: String,
    val status: Int,
    val createdAt: String,
    val price: Double,
    val details: List<OrderDetail>
) {
    val statusText: String
        get() = when (status) {
            0 -> "预订中"
            1 -> "预订成功"
            2 -> "预订取消"
            else -> "未知状态($status)"
        }

    val venueName: String
        get() = details.firstOrNull { it.serviceName.isNotBlank() }?.serviceName.orEmpty()

    /** 待支付订单可直接唤起支付引导。 */
    val canPay: Boolean get() = status == 0

    /** 服务端允许对预订中/预订成功订单发起取消。 */
    val canCancel: Boolean get() = status == 0 || status == 1
}

/** 订单分页响应。服务端不同部署可能返回数组或带 rows/object 的对象，统一成此模型。 */
data class OrderPage(
    val orders: List<OrderInfo>,
    val page: Int,
    val pageSize: Int,
    val total: Int? = null,
    val hasMore: Boolean = false
)

/** 取消订单/其它订单操作的统一结果。 */
data class OrderActionResult(
    val success: Boolean,
    val message: String
)

/** 滑动轨迹中的单个点 */
@Serializable
data class TrackPoint(
    val x: Int,
    val y: Int,
    val type: String,  // "down", "move", "up"
    val t: Long        // 相对时间戳（ms）
)

/** 滑动验证码结果，发给服务器验证；字段名是服务端协议的一部分（含拼写 entSlidingTime），不能改。 */
@Serializable
data class SliderResult(
    val bgImageWidth: Int,
    val bgImageHeight: Int,
    val sliderImageWidth: Int,
    val sliderImageHeight: Int,
    val startSlidingTime: String,   // ISO 8601
    val entSlidingTime: String,     // ISO 8601
    val trackList: List<TrackPoint>
) {
    fun toJson(): String = AppJson.encodeToString(this)
}

/** 自动识别（[VenueScreen] 的 `captchaHost` 槽位）交给共享预订状态机的结果。 */
data class SolvedCaptcha(
    /** 直接交给 [VenueSource.submitBooking] 的那份（时间戳已按「验证码出现时刻」盖好）。 */
    val sliderResult: SliderResult,
    /** 松手相对「验证码出现」过了多少毫秒 —— 状态机按这个节奏等一等再提交。 */
    val releaseAfterMillis: Long,
)
