package com.xjtu.toolbox.lms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LmsDownloadRecord(
    val name: String,
    val mimeType: String,
    val uri: String,
    val savedAt: Long
)

object LmsDownloadStore {
    private const val PREFS = "lms_downloads"
    private const val KEY_RECORDS = "records"

    fun getAll(context: Context): List<LmsDownloadRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        LmsDownloadRecord(
                            name = item.getString("name"),
                            mimeType = item.optString("mimeType", "application/octet-stream"),
                            uri = item.getString("uri"),
                            savedAt = item.optLong("savedAt")
                        )
                    )
                }
            }.sortedByDescending { it.savedAt }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, record: LmsDownloadRecord) {
        val records = (listOf(record) + getAll(context).filterNot { it.uri == record.uri }).take(100)
        save(context, records)
    }

    fun remove(context: Context, uri: String) {
        save(context, getAll(context).filterNot { it.uri == uri })
    }

    private fun save(context: Context, records: List<LmsDownloadRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("name", record.name)
                    .put("mimeType", record.mimeType)
                    .put("uri", record.uri)
                    .put("savedAt", record.savedAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }
}
