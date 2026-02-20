package com.xjtu.toolbox.jwapp

import android.util.Log
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import okhttp3.FormBody
import okhttp3.Request

private const val TAG = "CjcxApi"

/**
 * JWXT 精确成绩查询 (xscjcx.do)
 * ZCJ/XFJD 对所有课程（含等级制）均有精确数值，优于 JWAPP termScore
 */
class CjcxApi(private val login: JwxtLogin) {

    private val baseUrl = "https://jwxt.xjtu.edu.cn/jwapp/sys/cjcx"

    @Volatile private var lastSessionTime = 0L
    private val sessionTtl = 5 * 60 * 1000L // 5分钟内不重复初始化

    data class CjcxScore(
        val courseName: String,
        val termCode: String,
        val zcj: Double,
        val xfjd: Double,
        val xf: Double,
        val djcjlxdm: String,
        val djcjmc: String?,
        val pscj: Double?,
        val pscjxs: String?,
        val qmcj: Double?,
        val qmcjxs: String?,
        val kch: String,
        val jxbid: String,
        val passFlag: Boolean,
        val examProp: String,
        val kclbdm: String,
    )

    private fun ensureSession() {
        val now = System.currentTimeMillis()
        if (now - lastSessionTime < sessionTtl) return
        try {
            login.client.newCall(
                Request.Builder().url("$baseUrl/*default/index.do").get().build()
            ).execute().close()
            lastSessionTime = now
        } catch (e: Exception) {
            Log.w(TAG, "session init: ${e.message}")
        }
    }

    /** 获取全部有效成绩（自动分页） */
    fun getAllScores(): List<CjcxScore> {
        ensureSession()
        val all = mutableListOf<CjcxScore>()
        var page = 1
        val pageSize = 100

        while (true) {
            val querySetting = """[{"name":"SFYX","caption":"是否有效","linkOpt":"AND","builderList":"cbl_m_List","builder":"m_value_equal","value":"1","value_display":"是"}]"""
            val request = Request.Builder()
                .url("$baseUrl/modules/cjcx/xscjcx.do")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(FormBody.Builder()
                    .add("querySetting", querySetting)
                    .add("pageSize", pageSize.toString())
                    .add("pageNumber", page.toString())
                    .build())
                .build()

            val body = login.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("xscjcx.do HTTP ${resp.code}")
                resp.body?.string() ?: throw RuntimeException("空响应")
            }
            val root = body.safeParseJsonObject()
            if (root.get("code")?.asString != "0") {
                throw RuntimeException("xscjcx.do 业务错误: ${root.get("code")}")
            }

            val xscjcx = root.getAsJsonObject("datas").getAsJsonObject("xscjcx")
            val totalSize = xscjcx.get("totalSize").asInt
            val rows = xscjcx.getAsJsonArray("rows")

            for (el in rows) {
                val o = el.asJsonObject
                all.add(CjcxScore(
                    courseName = o.get("KCM").safeString(),
                    termCode = o.get("XNXQDM").safeString(),
                    zcj = o.get("ZCJ").safeDouble(),
                    xfjd = o.get("XFJD").safeDouble(),
                    xf = o.get("XF").safeDouble(),
                    djcjlxdm = o.get("DJCJLXDM").safeString(),
                    djcjmc = o.get("DJCJMC").safeStringOrNull()?.takeIf { it.isNotBlank() },
                    pscj = o.get("PSCJ").safeDouble(-999.0).takeIf { it > -100 },
                    pscjxs = o.get("PSCJXS").safeStringOrNull()?.takeIf { it.isNotBlank() && it != "0" },
                    qmcj = o.get("QMCJ").safeDouble(-999.0).takeIf { it > -100 },
                    qmcjxs = o.get("QMCJXS").safeStringOrNull()?.takeIf { it.isNotBlank() && it != "0" },
                    kch = o.get("KCH").safeString(),
                    jxbid = o.get("JXBID").safeString(),
                    passFlag = o.get("SFJG").safeString() == "1",
                    examProp = o.get("CXCKDM_DISPLAY").safeString(),
                    kclbdm = o.get("KCLBDM_DISPLAY").safeString(),
                ))
            }

            if (all.size >= totalSize || rows.size() < pageSize) break
            page++
        }

        Log.d(TAG, "获取 ${all.size} 门精确成绩")
        return all
    }

    /** 构建查找表：termCode|normalizedName → CjcxScore */
    fun buildLookup(scores: List<CjcxScore>): Map<String, CjcxScore> =
        scores.associateBy { "${it.termCode}|${normalizeName(it.courseName)}" }

    companion object {
        /** 标准化课程名（空格/全角/罗马数字/符号统一） */
        fun normalizeName(name: String): String = name.trim()
            .replace("\u3000", " ").replace("\u00A0", " ")
            .replace(Regex("\\s+"), " ")
            .replace("（", "(").replace("）", ")")
            .replace("＋", "+").replace("－", "-")
            .replace("Ⅰ", "I").replace("Ⅱ", "II").replace("Ⅲ", "III")
            .replace("Ⅳ", "IV").replace("Ⅴ", "V").replace("Ⅵ", "VI")
            .replace("ⅰ", "I").replace("ⅱ", "II").replace("ⅲ", "III")
            .replace("ⅳ", "IV").replace("ⅴ", "V").replace("ⅵ", "VI")
            .replace(Regex("[◇◆◎○●★☆※▲△▼▽]"), "")
            .replace(Regex("\\([A-Z]{2,}\\d{4,}\\)$"), "")
            .trim().lowercase()
    }
}
