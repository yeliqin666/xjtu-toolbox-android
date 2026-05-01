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
private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L

object HolidayApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 内存缓存
    private var cachedHolidays: Map<LocalDate, String>? = null

    suspend fun getHolidayDates(context: Context? = null): Map<LocalDate, String> = withContext(Dispatchers.IO) {
        cachedHolidays?.let { return@withContext it }
        val cache = context?.applicationContext?.let { DataCache(it) }
        cache?.get(CACHE_KEY, CACHE_TTL_MS)?.let { cachedJson ->
            parseCachedHolidays(cachedJson)?.let { holidays ->
                cachedHolidays = holidays
                Log.d(TAG, "Loaded holidays from disk cache: ${holidays.size} days")
                return@withContext holidays
            }
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
        
        cachedHolidays = holidays
        if (holidays.isNotEmpty()) {
            cache?.put(CACHE_KEY, serializeHolidays(holidays))
        }
        return@withContext holidays
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
