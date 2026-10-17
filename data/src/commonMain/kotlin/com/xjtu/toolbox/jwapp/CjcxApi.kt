package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.requireObj
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.stringValue
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request

private const val TAG = "CjcxApi"

/**
 * JWXT 精确成绩查询 (xscjcx.do)
 * ZCJ/XFJD 对所有课程（含等级制）均有精确数值，优于 JWAPP termScore
 */
class CjcxApi(private val site: SiteSession) {

    private val baseUrl = "https://jwxt.xjtu.edu.cn/jwapp/sys/cjcx"

    @Volatile private var lastSessionTime = 0L
    private val sessionTtl = 5 * 60 * 1000L // 5分钟内不重复初始化

    private suspend fun execute(request: Request): String =
        site.executeWithReAuth(request).use { response ->
            if (!response.isSuccessful) throw RuntimeException("xscjcx.do HTTP ${response.code}")
            response.body?.string() ?: throw RuntimeException("空响应")
        }

    private suspend fun ensureSession() {
        val now = System.currentTimeMillis()
        if (now - lastSessionTime < sessionTtl) return
        try {
            site.executeWithReAuth(Request.Builder().url("$baseUrl/*default/index.do").get().build()).close()
            lastSessionTime = now
        } catch (e: Exception) {
            Log.w(TAG, "session init: ${e.message}")
        }
    }

    /** 获取全部有效成绩（自动分页） */
    suspend fun getAllScores(): List<CjcxScore> {
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

            val body = execute(request)
            val root = body.safeParseJsonObject()
            if (root.get("code")?.stringValue != "0") {
                throw RuntimeException("xscjcx.do 业务错误: ${root.get("code")}")
            }

            val xscjcx = root.requireObj("datas").requireObj("xscjcx")
            val totalSize = xscjcx.get("totalSize").intValue
            val rows = xscjcx.requireArr("rows")

            for (el in rows) {
                val o = el.jsonObject
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

            if (all.size >= totalSize || rows.size < pageSize) break
            page++
            if (page > 50) break // 安全上限，防止服务端异常导致无限分页
        }

        Log.d(TAG, "获取 ${all.size} 门精确成绩")
        return all
    }
}
