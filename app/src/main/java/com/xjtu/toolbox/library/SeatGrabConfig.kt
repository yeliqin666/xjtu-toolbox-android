package com.xjtu.toolbox.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 定时抢座配置 — 持久化到 SharedPreferences (JSON)
 */
data class SeatGrabConfig(
    val enabled: Boolean = false,
    val targetSeats: List<TargetSeat> = emptyList(),
    /** 触发时刻 HH:mm:ss 格式，默认 22:00:00 */
    val triggerTimeStr: String = "22:00:00",
    /** 最大重试次数 */
    val maxRetries: Int = 5,
    /** 重试间隔（毫秒） */
    val retryIntervalMs: Long = 2000,
    /** 已有预约时自动换座 */
    val autoSwap: Boolean = true,
)

data class TargetSeat(
    val seatId: String,      // 如 "A101"
    val areaCode: String,    // 如 "north2elian"
    val areaName: String = "",// 如 "二层连廊"
    val priority: Int = 0    // 越小越优先
)

/**
 * 抢座配置持久化工具
 */
object SeatGrabConfigStore {
    private const val PREFS_NAME = "seat_grab_config"
    private const val KEY_CONFIG = "config_json"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, config: SeatGrabConfig) {
        prefs(context).edit()
            .putString(KEY_CONFIG, Gson().toJson(config))
            .apply()
    }

    fun load(context: Context): SeatGrabConfig {
        val json = prefs(context).getString(KEY_CONFIG, null) ?: return SeatGrabConfig()
        return try {
            val raw = Gson().fromJson(json, SeatGrabConfig::class.java)
            // Gson 绕过 Kotlin 构造函数，缺失字段会得到 null 而非默认值，需防御
            raw.copy(
                targetSeats = raw.targetSeats ?: emptyList(),
                triggerTimeStr = raw.triggerTimeStr ?: "22:00:00",
                maxRetries = raw.maxRetries.coerceIn(1, 20),
                retryIntervalMs = raw.retryIntervalMs.coerceIn(500, 30000)
            )
        } catch (_: Exception) {
            SeatGrabConfig()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
