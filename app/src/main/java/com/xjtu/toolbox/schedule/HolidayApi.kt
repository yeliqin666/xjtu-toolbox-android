package com.xjtu.toolbox.schedule

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.xjtu.toolbox.util.DataCache
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "HolidayApi"
private const val CACHE_KEY = "holiday_dates"

object HolidayApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 内存缓存
    private var cachedHolidays: Map<LocalDate, String>? = null

    /** 只读内存/磁盘，不访问网络。课表首屏不能被节假日接口拖住。 */
    fun peekCached(context: Context? = null): Map<LocalDate, String> {
        cachedHolidays?.let { return it }
        val cache = context?.applicationContext?.let { DataCache(it) }
        cache?.get(CACHE_KEY, Long.MAX_VALUE)?.let { cachedJson ->
            parseCachedHolidays(cachedJson)?.let { holidays ->
                cachedHolidays = holidays
                Log.d(TAG, "Loaded holidays from disk cache: ${holidays.size} days")
                return holidays
            }
        }
        return emptyMap()
    }

    suspend fun getHolidayDates(
        context: Context? = null,
        forceRefresh: Boolean = false
    ): Map<LocalDate, String> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = peekCached(context)
            if (cached.isNotEmpty()) return@withContext cached
        }
        val cache = context?.applicationContext?.let { DataCache(it) }
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
            val years = root.getAsJsonObject("Years")
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            
            years.keySet().forEach { yearStr ->
                val yearArray = years.getAsJsonArray(yearStr)
                yearArray.forEach { ev ->
                    val obj = ev.asJsonObject
                    val name = obj.get("Name").asString
                    val startStr = obj.get("StartDate").asString
                    val endStr = obj.get("EndDate").asString
                    val startDate = LocalDate.parse(startStr, formatter)
                    val endDate = LocalDate.parse(endStr, formatter)
                    
                    var curr = startDate
                    while (!curr.isAfter(endDate)) {
                        holidays[curr] = name
                        curr = curr.plusDays(1)
                    }
                }
            }
            Log.d(TAG, "Fetched holidays from primary API: \${holidays.size} days")
            
        } catch (e: Exception) {
            Log.w(TAG, "Primary holiday API failed, trying fallback...", e)
            try {
                // 后备接口：holiday-cn
                val currentYear = LocalDate.now().year
                val yearsToFetch = listOf(currentYear, currentYear + 1)
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                
                for (y in yearsToFetch) {
                    try {
                        val request = Request.Builder()
                            .url("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/\$y.json")
                            .build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val jsonStr = response.body?.string() ?: continue
                            val root = jsonStr.safeParseJsonObject()
                            val days = root.getAsJsonArray("days")
                            days.forEach { dayItem ->
                                val dObj = dayItem.asJsonObject
                                if (dObj.get("isOffDay").asBoolean) {
                                    val dateStr = dObj.get("date").asString
                                    val nameObj = dObj.get("name")
                                    val name = if (nameObj != null && !nameObj.isJsonNull) nameObj.asString else "节假日"
                                    holidays[LocalDate.parse(dateStr, formatter)] = name
                                }
                            }
                        }
                    } catch (innerE: Exception) {
                        Log.e(TAG, "Fallback API failed for year \$y", innerE)
                    }
                }
                Log.d(TAG, "Fetched holidays from fallback API: \${holidays.size} days")
            } catch (e2: Exception) {
                Log.e(TAG, "Both holiday APIs failed", e2)
            }
        }
        
        if (holidays.isNotEmpty()) {
            cachedHolidays = holidays
            cache?.put(CACHE_KEY, serializeHolidays(holidays))
            return@withContext holidays
        }

        cachedHolidays?.let {
            Log.d(TAG, "Using in-memory holiday cache after refresh failure: ${it.size} days")
            return@withContext it
        }
        cache?.get(CACHE_KEY, Long.MAX_VALUE)?.let { cachedJson ->
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
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            root.entrySet().associate { (date, name) ->
                LocalDate.parse(date, formatter) to name.asString
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached holidays", e)
            null
        }
    }

    private fun serializeHolidays(holidays: Map<LocalDate, String>): String {
        val root = JsonObject()
        holidays.forEach { (date, name) ->
            root.addProperty(date.toString(), name)
        }
        return root.toString()
    }
}
