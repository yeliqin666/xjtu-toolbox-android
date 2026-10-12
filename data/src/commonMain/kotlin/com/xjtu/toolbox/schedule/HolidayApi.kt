package com.xjtu.toolbox.schedule

import kotlinx.serialization.json.JsonPrimitive
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.requireObj
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.booleanValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.obj
import kotlinx.serialization.json.jsonObject
import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xjtu.toolbox.util.todayInSystemZone
import okhttp3.Request
import kotlinx.datetime.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "HolidayApi"
private const val CACHE_KEY = "holiday_dates"

/**
 * 全国节假日（免费公开接口：主源 shuyz 的 holidayAPI.json，后备 NateScarlet/holiday-cn）。
 * 课表拿它把「放假那天的课」隐掉（`ScheduleCache.filterByHolidays`）。
 *
 * 从 `:app` 的 `schedule/HolidayApi.kt` 搬进 `:data`（`:core` 的日程页与它都要用），改了四处：
 *  1. `android.util.Log` → `:core` 的 `Log`（既有先例）；
 *  2. 参数从 `Context?` 换成 [ScheduleCacheStore]? —— 它要的只是那份 JSON 落盘缓存。
 *     `:app` 传的就是原来那个 `DataCache`（键 `holiday_dates` 与存进去的 JSON 逐字未动），
 *     桌面端传键值文件那一份；
 *  3. 日期类型 `java.time.LocalDate` → `kotlinx.datetime.LocalDate`（屏与 VM 进了 `:core`）；
 *     解析从 `DateTimeFormatter` 换成 `LocalDate.parse`（ISO，两边对 `yyyy-MM-dd` 同口径），
 *     `toString()` 仍是 ISO ⇒ **老缓存逐字节读得回来**；
 *  4. `LocalDate.now()` → [todayInSystemZone]（`:core` 的「本机时区的今天」，同一个系统默认时区）。
 */
object HolidayApi {
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 内存缓存
    private var cachedHolidays: Map<LocalDate, String>? = null

    /** 只读内存/磁盘，不访问网络。课表首屏不能被节假日接口拖住。 */
    fun peekCached(cache: ScheduleCacheStore? = null): Map<LocalDate, String> {
        cachedHolidays?.let { return it }
        cache?.readJson(CACHE_KEY, Long.MAX_VALUE)?.let { cachedJson ->
            parseCachedHolidays(cachedJson)?.let { holidays ->
                cachedHolidays = holidays
                Log.d(TAG, "Loaded holidays from disk cache: ${holidays.size} days")
                return holidays
            }
        }
        return emptyMap()
    }

    suspend fun getHolidayDates(
        cache: ScheduleCacheStore? = null,
        forceRefresh: Boolean = false
    ): Map<LocalDate, String> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = peekCached(cache)
            if (cached.isNotEmpty()) return@withContext cached
        }
        val holidays = mutableMapOf<LocalDate, String>()
        
        try {
            // 尝试主接口：china-holiday-calender
            val request = Request.Builder()
                .url("https://www.shuyz.com/githubfiles/china-holiday-calender/master/holidayAPI.json")
                .build()
            val jsonStr = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RuntimeException("Primary API failed")
                response.body?.string() ?: throw RuntimeException("Empty response")
            }
            val root = jsonStr.safeParseJsonObject()
            val years = root.requireObj("Years")
            
            years.keys.forEach { yearStr ->
                val yearArray = years.requireArr(yearStr)
                yearArray.forEach { ev ->
                    val obj = ev.jsonObject
                    val name = obj.get("Name").stringValue
                    val startStr = obj.get("StartDate").stringValue
                    val endStr = obj.get("EndDate").stringValue
                    val startDate = LocalDate.parse(startStr)
                    val endDate = LocalDate.parse(endStr)
                    
                    var curr = startDate
                    while (curr <= endDate) {
                        holidays[curr] = name
                        curr = civilFromEpochDay(curr.toEpochDays().toLong() + 1)
                    }
                }
            }
            Log.d(TAG, "Fetched holidays from primary API: ${holidays.size} days")
        } catch (e: Exception) {
            Log.w(TAG, "Primary holiday API failed, trying fallback...", e)
            try {
                // 后备接口：holiday-cn
                val currentYear = todayInSystemZone().year
                val yearsToFetch = listOf(currentYear, currentYear + 1)
                
                for (y in yearsToFetch) {
                    try {
                        val request = Request.Builder()
                            .url("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$y.json")
                            .build()
                        val jsonStr = client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) response.body?.string() else null
                        } ?: continue
                        val root = jsonStr.safeParseJsonObject()
                        val days = root.requireArr("days")
                        days.forEach { dayItem ->
                            val dObj = dayItem.jsonObject
                            if (dObj.get("isOffDay").booleanValue) {
                                val dateStr = dObj.get("date").stringValue
                                val nameObj = dObj.get("name")
                                val name = if (nameObj != null && !nameObj.isNull) nameObj.stringValue else "节假日"
                                holidays[LocalDate.parse(dateStr)] = name
                            }
                        }
                    } catch (innerE: Exception) {
                        Log.e(TAG, "Fallback API failed for year $y", innerE)
                    }
                }
                Log.d(TAG, "Fetched holidays from fallback API: ${holidays.size} days")
            } catch (e2: Exception) {
                Log.e(TAG, "Both holiday APIs failed", e2)
            }
        }
        
        if (holidays.isNotEmpty()) {
            cachedHolidays = holidays
            cache?.writeJson(CACHE_KEY, serializeHolidays(holidays))
            return@withContext holidays
        }

        cachedHolidays?.let {
            Log.d(TAG, "Using in-memory holiday cache after refresh failure: ${it.size} days")
            return@withContext it
        }
        cache?.readJson(CACHE_KEY, Long.MAX_VALUE)?.let { cachedJson ->
            parseCachedHolidays(cachedJson)?.let { cached ->
                cachedHolidays = cached
                Log.d(TAG, "Using disk holiday cache after refresh failure: ${cached.size} days")
                return@withContext cached
            }
        }
        return@withContext emptyMap()
    }

    private fun parseCachedHolidays(json: String): Map<LocalDate, String>? {
        return try {
            val root = json.safeParseJsonObject()
            root.entries.associate { (date, name) ->
                LocalDate.parse(date) to name.stringValue
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached holidays", e)
            null
        }
    }

    private fun serializeHolidays(holidays: Map<LocalDate, String>): String {
        return JsonObject(holidays.entries.associate { (date, name) -> date.toString() to JsonPrimitive(name) }).toString()
    }
}
