package com.xjtu.toolbox.emptyroom

import android.content.Context
import com.xjtu.toolbox.util.safeParseJsonObject
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
class EmptyRoomApi(context: Context? = null) {

    private val cdnBaseUrl = "https://gh-release.xjtutoolbox.com/"

    private val client = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 缓存：日期 → 完整 JSON 数据
    private var cachedDate: String? = null
    private var cachedFetchedDay: String? = null
    private var cachedData: com.google.gson.JsonObject? = null
    private val cache = context?.let { EmptyRoomCache(it) }

    /**
     * 获取指定日期的空闲教室数据
     * @param date 日期，格式 YYYY-MM-DD
     */
    private fun fetchDayData(date: String): com.google.gson.JsonObject {
        // 命中缓存
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        if (date == cachedDate && cachedFetchedDay == today && cachedData != null) {
            return cachedData!!
        }
        cache?.readJson("cdn_day_$date", EmptyRoomCache.CDN_RESULT_TTL_DAYS)?.let {
            val json = it.safeParseJsonObject()
            cachedDate = date
            cachedFetchedDay = today
            cachedData = json
            return json
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

        val json = body.safeParseJsonObject()
        cache?.writeJson("cdn_day_$date", body)
        cachedDate = date
        cachedFetchedDay = today
        cachedData = json
        return json
    }

    /**
     * 查询多个教学楼的教室信息（合并结果）
     */
    fun getEmptyRoomsMulti(
        campusName: String,
        buildingNames: Set<String>,
        date: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    ): List<RoomInfo> {
        if (buildingNames.isEmpty()) return emptyList()
        val data = fetchDayData(date)
        val campusData = data.getAsJsonObject(campusName)
            ?: throw NoDataException("暂无 $campusName 的数据")
        return buildingNames.flatMap { buildingName ->
            val buildingData = campusData.getAsJsonObject(buildingName) ?: return@flatMap emptyList()
            buildingData.entrySet()
                .filter { (key, value) -> key != "null" && key.isNotBlank() && !value.isJsonNull }
                .mapNotNull { (roomName, roomJson) ->
                    try {
                        val obj = roomJson.asJsonObject
                        val status = obj.getAsJsonArray("status").map { it.asInt }
                        val size = obj.get("size")?.let { if (it.isJsonNull) 0 else it.asInt } ?: 0
                        RoomInfo(name = roomName, size = size, status = status)
                    } catch (_: Exception) { null }
                }
        }.sortedBy { it.name }
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

    /**
     * 根据教室全名（如 "主楼A-301"）查找其座位数。
     * 遍历所有校区→教学楼→教室，尝试匹配教室名。
     * @return 座位数，如未找到或CDN无数据则返回 null
     */
    fun getRoomSeatCount(location: String): Int? {
        if (location.isBlank()) return null
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val data = try { fetchDayData(date) } catch (_: Exception) { return null }

        for ((_, campusJson) in data.entrySet()) {
            val campusObj = try { campusJson.asJsonObject } catch (_: Exception) { continue }
            for ((buildingName, buildingJson) in campusObj.entrySet()) {
                val buildingObj = try { buildingJson.asJsonObject } catch (_: Exception) { continue }
                // 尝试1: location 以 "教学楼-教室号" 形式，如 "主楼A-301" → buildingName="主楼A", room="301"
                if (location.startsWith(buildingName)) {
                    val roomPart = location.removePrefix(buildingName).trimStart('-', ' ', '/')
                    if (roomPart.isNotBlank() && buildingObj.has(roomPart)) {
                        val size = buildingObj.getAsJsonObject(roomPart).get("size")
                            ?.let { if (it.isJsonNull) null else it.asInt }
                        if (size != null && size > 0) return size
                    }
                }
                // 尝试2: location 直接就是 room key
                if (buildingObj.has(location)) {
                    val size = buildingObj.getAsJsonObject(location).get("size")
                        ?.let { if (it.isJsonNull) null else it.asInt }
                    if (size != null && size > 0) return size
                }
            }
        }
        return null
    }
}

/**
 * 无数据异常（CDN 上没有该天的数据）
 */
class NoDataException(message: String) : Exception(message)

// =====================================================================================
// 直连查询：通过教务系统接口实时查询空闲教室。
// =====================================================================================


/**
 * 教务系统返回的原始空闲教室数据。
 */
data class DirectRoomRow(
    val name: String,
    val buildingName: String,
    val type: String?,
    val capacity: Int,
    val examCapacity: Int,
    val campusName: String
)

/**
 * 不依赖 CDN 的空闲教室直连查询。
 *
 * 使用教务系统 jwxt.xjtu.edu.cn 的真实接口。
 * 必须先将账户角色切换到「学生」（移动应用学生身份下接口不可用），构造时自动完成。
 *
 * @param httpClient 已通过 JWXT 认证的 OkHttpClient（共享自 sharedClient 或 vpnClient）
 */
class EmptyRoomDirectQuery(private val httpClient: OkHttpClient, private val cache: EmptyRoomCache? = null) {

    companion object {
        private const val TAG = "EmptyRoomDirect"
        private const val JWXT_BASE = "https://jwxt.xjtu.edu.cn"
        private const val REFERER = "$JWXT_BASE/jwapp/sys/kxjas/*default/index.do"
        private const val FORM_CT = "application/x-www-form-urlencoded; charset=UTF-8"
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        // 上游 jwxt/empty_room.py 的 UUID（不可变）
        private const val CAMPUS_CODE_API = "$JWXT_BASE/jwapp/code/83a986fc-e677-400e-99a4-c7bb39c2ca35.do"
        private const val BUILDING_CODE_API = "$JWXT_BASE/jwapp/code/551fbcc3-cf07-4566-af1e-fc7ce272ddc1.do"
        private const val QUERY_API = "$JWXT_BASE/jwapp/sys/kxjas/modules/kxjscx/cxkxjs.do"
        // 角色相关
        private const val USER_INFO_API = "$JWXT_BASE/jwapp/sys/homeapp/api/home/currentUser.do"
        private const val CHANGE_ROLE_API = "$JWXT_BASE/jwapp/sys/homeapp/api/home/changeAppRole.do"

        /** 进程内缓存：校区/教学楼代码 7 天有效（命名编号几乎不变） */
        private const val CODE_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        @Volatile private var cachedCampusCodes: Pair<Map<String, String>, Long>? = null
        @Volatile private var cachedBuildingCodes: Pair<Map<String, String>, Long>? = null
    }

    @Volatile private var roleEnsuredStudent: Boolean = false

    /**
     * 切换到「学生」身份（仅当前不是时）。仅在第一次调用查询接口前执行一次。
     */
    fun ensureRoleStudent() {
        if (roleEnsuredStudent) return
        try {
            val resp = httpClient.newCall(
                Request.Builder()
                    .url(USER_INFO_API)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Referer", "$JWXT_BASE/jwapp/sys/homeapp/home/index.html?av=&contextPath=/jwapp")
                    .header("User-Agent", BROWSER_UA)
                    .get()
                    .build()
            ).execute()
            val body = resp.body?.string() ?: return
            val json = body.safeParseJsonObject()
            val datas = json.getAsJsonObject("datas") ?: return
            val groups = datas.getAsJsonArray("userGroups") ?: return
            var currentRoleName: String? = null
            var studentRoleId: String? = null
            groups.forEach { el ->
                val o = el.asJsonObject
                val roleName = o.get("roleName")?.asString
                val roleId = o.get("roleId")?.asString
                val isCurrent = o.get("currentRole")?.takeIf { !it.isJsonNull }?.asBoolean == true
                if (isCurrent) currentRoleName = roleName
                if (roleName == "学生") studentRoleId = roleId
            }
            if (currentRoleName != "学生" && studentRoleId != null) {
                android.util.Log.d(TAG, "switching role $currentRoleName → 学生 ($studentRoleId)")
                val form = okhttp3.FormBody.Builder().add("appRole", studentRoleId!!).build()
                httpClient.newCall(
                    Request.Builder().url(CHANGE_ROLE_API).post(form).build()
                ).execute().close()
            }
            roleEnsuredStudent = true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ensureRoleStudent failed (will continue anyway)", e)
        }
    }

    /** 获取校区名→code 映射（带 7 天缓存） */
    fun getCampusCodes(): Map<String, String> {
        cachedCampusCodes?.let { (data, ts) ->
            if (System.currentTimeMillis() - ts < CODE_CACHE_TTL_MS) return data
        }
        cache?.readCodeMap("direct_campus_codes", EmptyRoomCache.CODE_TTL_DAYS)?.let {
            cachedCampusCodes = it to System.currentTimeMillis()
            return it
        }
        ensureRoleStudent()
        val resp = httpClient.newCall(
            Request.Builder()
                .url(CAMPUS_CODE_API)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", FORM_CT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", REFERER)
                .header("User-Agent", BROWSER_UA)
                .post(okhttp3.FormBody.Builder().build())
                .build()
        ).execute()
        if (!resp.isSuccessful) throw RuntimeException("校区代码请求失败: HTTP ${resp.code}")
        val body = resp.body?.string().orEmpty()
        val map = parseCodeMap(body)
        android.util.Log.d(TAG, "campus code count=${map.size}, bodyPrefix=${body.take(160)}")
        if (map.isEmpty()) throw RuntimeException("校区代码为空")
        cache?.writeCodeMap("direct_campus_codes", map)
        cachedCampusCodes = map to System.currentTimeMillis()
        return map
    }

    /** 获取教学楼名→code 映射（带 7 天缓存） */
    fun getBuildingCodes(): Map<String, String> {
        cachedBuildingCodes?.let { (data, ts) ->
            if (System.currentTimeMillis() - ts < CODE_CACHE_TTL_MS) return data
        }
        cache?.readCodeMap("direct_building_codes", EmptyRoomCache.CODE_TTL_DAYS)?.let {
            cachedBuildingCodes = it to System.currentTimeMillis()
            return it
        }
        ensureRoleStudent()
        val resp = httpClient.newCall(
            Request.Builder()
                .url(BUILDING_CODE_API)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", FORM_CT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", REFERER)
                .header("User-Agent", BROWSER_UA)
                .post(okhttp3.FormBody.Builder().build())
                .build()
        ).execute()
        if (!resp.isSuccessful) throw RuntimeException("教学楼代码请求失败: HTTP ${resp.code}")
        val body = resp.body?.string().orEmpty()
        val map = parseCodeMap(body)
        android.util.Log.d(TAG, "building code count=${map.size}, bodyPrefix=${body.take(160)}")
        if (map.isEmpty()) throw RuntimeException("教学楼代码为空")
        cache?.writeCodeMap("direct_building_codes", map)
        cachedBuildingCodes = map to System.currentTimeMillis()
        return map
    }

    private fun parseCodeMap(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val rows = body.safeParseJsonObject()
                .getAsJsonObject("datas")
                ?.getAsJsonObject("code")
                ?.getAsJsonArray("rows") ?: return emptyMap()
            for (el in rows) {
                val o = el.asJsonObject
                val name = firstString(o, "name", "NAME", "text", "label", "MC", "DM_DISPLAY") ?: continue
                val id = firstString(o, "id", "ID", "value", "code", "DM") ?: continue
                out[name] = id
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "parseCodeMap failed", e)
        }
        return out
    }

    private fun firstString(obj: com.google.gson.JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val el = obj.get(key)
            if (el != null && !el.isJsonNull) {
                val value = el.asString
                if (value.isNotBlank()) return value
            }
        }
        return null
    }
    /**
     * 单次查询：某校区某栋楼某天 [start,end] 节次的空闲教室。
     * - startTime/endTime 为 0 表示「无过滤」，返回全楼所有教室（用于得到完整教室列表）。
     */
    fun queryRooms(
        campusCode: String,
        buildingCode: String,
        date: String,
        startTime: Int,
        endTime: Int
    ): List<DirectRoomRow> {
        ensureRoleStudent()
        val form = okhttp3.FormBody.Builder()
            .add("XXXQDM", campusCode)
            .add("JXLDM", buildingCode)
            .add("KXRQ", date)
            .add("KSJC", startTime.toString())
            .add("JSJC", endTime.toString())
            .add("pageSize", "500")
            .add("pageNumber", "1")
            .build()
        val resp = httpClient.newCall(
            Request.Builder().url(QUERY_API)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", FORM_CT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", REFERER)
                .header("User-Agent", BROWSER_UA)
                .post(form)
                .build()
        ).execute()
        if (!resp.isSuccessful) throw RuntimeException("空闲教室查询失败: HTTP ${resp.code}")
        val body = resp.body?.string().orEmpty()
        android.util.Log.d(TAG, "queryRooms date=$date start=$startTime end=$endTime http=${resp.code} bodyPrefix=${body.take(120)}")
        if (!body.trimStart().startsWith("{")) {
            throw RuntimeException("空闲教室查询响应异常")
        }
        val root = body.safeParseJsonObject()
        val datas = root.getAsJsonObject("datas")
            ?: throw RuntimeException("空闲教室查询响应缺少 datas")
        val rows = datas.getAsJsonObject("cxkxjs")
            ?.getAsJsonArray("rows")
            ?: throw RuntimeException("空闲教室查询响应缺少 rows")
        val out = ArrayList<DirectRoomRow>()
        for (el in rows) {
            val o = el.asJsonObject
            // 上游过滤：JASLXDM null（接口幻觉教室）/ JASMC 含「测试专用」
            if (o.get("JASLXDM")?.isJsonNull == true) continue
            val name = o.get("JASMC")?.asString ?: continue
            if ("测试专用" in name) continue
            out.add(
                DirectRoomRow(
                    name = name,
                    buildingName = o.get("JXLDM_DISPLAY")?.asString ?: "",
                    type = o.get("JASLXDM_DISPLAY")?.asString,
                    capacity = o.get("SKZWS")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    examCapacity = o.get("KSZWS")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    campusName = o.get("XXXQDM_DISPLAY")?.asString ?: ""
                )
            )
        }
        return out
    }

    /**
     * 全天查询：返回 [Map<roomName, RoomInfo>]，与现有 [RoomInfo] 数据结构兼容，
     * 便于直接喂给已有的 UI（11 节课的 status 数组：0=空闲，1=占用）。
     *
     * 流程：先用 startTime=0/endTime=0 拿全楼所有教室（默认占用），再对 1..11 节
     * 各查询一次，命中即把该节标记为空闲。
     */
    fun queryDay(
        campusName: String,
        buildingName: String,
        date: String,
        progress: ((Int, Int) -> Unit)? = null
    ): List<RoomInfo> {
        val cacheKey = "direct_day_${campusName}_${buildingName}_$date"
        cache?.readRoomList(cacheKey, EmptyRoomCache.DIRECT_RESULT_TTL_DAYS)?.let { return it }
        val campusCode = getCampusCodes()[campusName]
            ?: throw NoDataException("未知校区: $campusName")
        val buildingCode = getBuildingCodes()[buildingName]
            ?: throw NoDataException("未知教学楼: $buildingName")

        val all = queryRooms(campusCode, buildingCode, date, 0, 0)
        val result = LinkedHashMap<String, RoomInfo>()
        all.forEach { row ->
            result[row.name] = RoomInfo(
                name = row.name,
                size = row.capacity,
                status = MutableList(11) { 1 } // 默认全部占用
            )
        }
        for (period in 1..11) {
            progress?.invoke(period, 11)
            val freeNow = queryRooms(campusCode, buildingCode, date, period, period)
            freeNow.forEach { row ->
                result[row.name]?.let { info ->
                    val mut = info.status.toMutableList()
                    mut[period - 1] = 0
                    result[row.name] = info.copy(status = mut)
                }
            }
        }
        return result.values.sortedBy { it.name }.also {
            if (it.isNotEmpty()) cache?.writeRoomList(cacheKey, it)
        }
    }
}
