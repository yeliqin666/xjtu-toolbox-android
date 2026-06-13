package com.xjtu.toolbox.attendance

import android.content.Context
import com.google.gson.Gson

data class AttendanceSnapshot(
    val studentName: String,
    val termList: List<TermInfo>,
    val currentTermBh: String,
    val selectedTermBh: String,
    val records: List<AttendanceWaterRecord>,
    val courseStats: List<CourseAttendanceStat>,
    val savedAt: Long
)

object AttendanceCache {
    private val gson = Gson()

    private fun prefsName(postgraduate: Boolean) =
        if (postgraduate) "attendance_cache_postgraduate" else "attendance_cache_undergraduate"

    fun load(context: Context, postgraduate: Boolean): AttendanceSnapshot? {
        val raw = context.getSharedPreferences(prefsName(postgraduate), Context.MODE_PRIVATE)
            .getString("snapshot", null) ?: return null
        return runCatching { gson.fromJson(raw, AttendanceSnapshot::class.java) }.getOrNull()
    }

    fun save(context: Context, postgraduate: Boolean, snapshot: AttendanceSnapshot) {
        context.getSharedPreferences(prefsName(postgraduate), Context.MODE_PRIVATE)
            .edit()
            .putString("snapshot", gson.toJson(snapshot))
            .apply()
    }
}
