package com.xjtu.toolbox.lms

import android.content.Context
import android.content.ContentUris
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

data class LmsDownloadRecord(
    val name: String,
    val mimeType: String,
    val uri: String,
    val savedAt: Long,
    val category: String = LmsDownloadStore.CATEGORY_LMS
)

object LmsDownloadStore {
    private const val PREFS = "lms_downloads"
    private const val KEY_RECORDS = "records"
    const val CATEGORY_LMS = "lms"
    const val CATEGORY_TRANSCRIPT = "transcript"
    const val CATEGORY_OTHER = "other"
    const val PUBLIC_DIR_NAME = "XJTUToolBox"
    const val RELATIVE_PATH = "Download/$PUBLIC_DIR_NAME"
    const val RELATIVE_PATH_WITH_SLASH = "$RELATIVE_PATH/"
    private const val LEGACY_RELATIVE_PATH = "Download/岱宗盒子/"

    fun getAll(context: Context): List<LmsDownloadRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "[]") ?: "[]"
        val stored = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        LmsDownloadRecord(
                            name = item.getString("name"),
                            mimeType = item.optString("mimeType", "application/octet-stream"),
                            uri = item.getString("uri"),
                            savedAt = item.optLong("savedAt"),
                            category = item.optString("category", inferCategory(item.optString("name"), item.optString("mimeType")))
                        )
                    )
                }
            }.sortedByDescending { it.savedAt }
        }.getOrDefault(emptyList())
        val discovered = discoverDownloads(context)
        val merged = (stored + discovered)
            .distinctBy { it.uri }
            .sortedByDescending { it.savedAt }
            .take(100)
        if (merged.size != stored.size || merged.any { record -> stored.none { it.uri == record.uri } }) {
            save(context, merged)
        }
        return merged
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
                    .put("category", record.category)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }

    private fun discoverDownloads(context: Context): List<LmsDownloadRecord> = runCatching {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.RELATIVE_PATH
        )
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH} IN (?, ?)",
            arrayOf(RELATIVE_PATH_WITH_SLASH, LEGACY_RELATIVE_PATH),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mime = cursor.getString(mimeIndex).orEmpty().ifBlank { "application/octet-stream" }
                    val path = cursor.getString(pathIndex).orEmpty()
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex)
                    )
                    add(
                        LmsDownloadRecord(
                            name = name,
                            mimeType = mime,
                            uri = uri.toString(),
                            savedAt = cursor.getLong(dateIndex) * 1000L,
                            category = if (path == LEGACY_RELATIVE_PATH) CATEGORY_LMS else inferCategory(name, mime)
                        )
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun publicDisplayPath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME/"

    private fun inferCategory(name: String, mimeType: String): String {
        val lowerName = name.lowercase()
        val lowerMime = mimeType.lowercase()
        return when {
            lowerName.contains("成绩单") || lowerName.contains("transcript") -> CATEGORY_TRANSCRIPT
            lowerMime == "application/pdf" && (lowerName.contains("score") || lowerName.contains("grade")) -> CATEGORY_TRANSCRIPT
            else -> CATEGORY_LMS
        }
    }
}
