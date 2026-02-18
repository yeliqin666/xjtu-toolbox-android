package com.xjtu.toolbox.emptyroom

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 教室信息（来自 CDN 缓存）
 */
data class RoomInfo(
    val name: String,      // 教室名称，如 "主楼A-101"
    val size: Int,         // 座位数
    val status: List<Int>  // 11 个元素，对应 1-11 节课的占用情况：0=空闲, 1=占用
)

/**
 * 校区-教学楼映射（来自 XJTUToolBox）
 */
val CAMPUS_BUILDINGS = mapOf(
    "兴庆校区" to listOf(
        "主楼A", "主楼B", "主楼C", "主楼D", "中2", "中3",
        "西2东", "西2西", "外文楼A", "外文楼B", "东1东", "东2",
        "仲英楼", "东1西", "教2楼", "中1", "主楼E座",
        "工程馆", "工程坊A区", "文管", "计教中心", "田家炳"
    ),
    "雁塔校区" to listOf(
        "东配楼", "微免楼", "综合楼", "教学楼", "药学楼", "解剖楼",
        "生化楼", "病理楼", "西配楼", "一附院科教楼", "二院教学楼",
        "护理楼", "卫法楼"
    ),
    "曲江校区" to listOf("西一楼", "西五楼", "西四楼", "西六楼"),
    "创新港校区" to listOf("1", "2", "3", "4", "5", "9", "18", "19", "20", "21"),
    "苏州校区" to listOf("公共学院5号楼")
)

/**
 * 空闲教室查询 API — 从 Cloudflare CDN 获取预生成数据
 *
 * CDN 地址: https://gh-release.xjtutoolbox.com/?file=static/empty_room/{日期}.json
 * 数据由 XJTUToolBox GitHub Actions 每日自动更新
 * 无需登录，无需校园网
 */
class EmptyRoomApi {

    private val cdnBaseUrl = "https://gh-release.xjtutoolbox.com/"

    private val client = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 缓存：日期 → 完整 JSON 数据
    private var cachedDate: String? = null
    private var cachedData: com.google.gson.JsonObject? = null

    /**
     * 获取指定日期的空闲教室数据
     * @param date 日期，格式 YYYY-MM-DD
     */
    private fun fetchDayData(date: String): com.google.gson.JsonObject {
        // 命中缓存
        if (date == cachedDate && cachedData != null) {
            return cachedData!!
        }

        val request = Request.Builder()
            .url("${cdnBaseUrl}?file=static/empty_room/${date}.json")
            .get()
            .build()

        val response = client.newCall(request).execute()

        if (response.code == 404) {
            throw NoDataException("当天暂无空闲教室数据，请稍后再试")
        }

        if (!response.isSuccessful) {
            throw RuntimeException("请求失败: HTTP ${response.code}")
        }

        val body = response.body?.string()
            ?: throw RuntimeException("响应为空")

        val json = JsonParser.parseString(body).asJsonObject
        cachedDate = date
        cachedData = json
        return json
    }

    /**
     * 查询指定校区、教学楼的教室信息
     * @param campusName 校区名称（如 "兴庆校区"）
     * @param buildingName 教学楼名称（如 "主楼A"）
     * @param date 日期，格式 YYYY-MM-DD（默认今天）
     * @return 该教学楼所有教室的信息列表
     */
    fun getEmptyRooms(
        campusName: String,
        buildingName: String,
        date: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    ): List<RoomInfo> {
        val data = fetchDayData(date)

        val campusData = data.getAsJsonObject(campusName)
            ?: throw NoDataException("暂无 $campusName 的数据")

        val buildingData = campusData.getAsJsonObject(buildingName)
            ?: throw NoDataException("暂无 $campusName - $buildingName 的数据")

        return buildingData.entrySet()
            .filter { (key, value) -> key != "null" && key.isNotBlank() && !value.isJsonNull }
            .mapNotNull { (roomName, roomJson) ->
                try {
                    val obj = roomJson.asJsonObject
                    val status = obj.getAsJsonArray("status").map { it.asInt }
                    val size = obj.get("size")?.let { if (it.isJsonNull) 0 else it.asInt } ?: 0
                    RoomInfo(
                        name = roomName,
                        size = size,
                        status = status
                    )
                } catch (_: Exception) { null }
            }.sortedBy { it.name }
    }

    /**
     * 获取可查询的日期列表（今天和明天）
     */
    fun getAvailableDates(): List<String> {
        val today = LocalDate.now()
        return listOf(
            today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        )
    }
}

/**
 * 无数据异常（CDN 上没有该天的数据）
 */
class NoDataException(message: String) : Exception(message)
