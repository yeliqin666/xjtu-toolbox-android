package com.xjtu.toolbox.iclassface

import android.util.Log
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.XJTULogin
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDate

/**
 * 人脸识别签到查询 API（iclassface.xjtu.edu.cn）
 *
 * 只做「今天刷没刷上卡」这一件事：POST /checkin/records 提交日期，解析返回的
 * 服务端渲染 HTML 表格。不涉及 /wifi/location（教室定位，依赖真实校园网 WiFi
 * 环境，客户端无法稳定复刻，详见 PLAN.md）。
 */
class IclassfaceApi(private val site: SiteSession) {

    companion object {
        private const val TAG = "IclassfaceApi"
    }

    private suspend fun execute(builder: Request.Builder) =
        site.executeWithReAuth(builder.build())

    /** 一条签到/刷卡记录 */
    data class CheckinRecord(
        val name: String,
        val studentNo: String,
        val time: String,       // "2026-06-04 14:14:37"
        val location: String,   // "中3-2306"
        val type: String        // "电子班牌刷卡" / "电子班牌人脸识别"
    )

    /**
     * 查询指定日期的签到记录（默认今天）。
     * 返回空列表表示当天确实没有任何记录（含"还没刷上卡"的情况）。
     */
    suspend fun fetchRecords(date: LocalDate = LocalDate.now()): List<CheckinRecord> {
        val dateStr = date.toString()
        val formBody = FormBody.Builder().add("date", dateStr).build()
        val response = execute(
            Request.Builder()
                .url(IclassfaceLogin.CHECKIN_URL)
                .header("Referer", IclassfaceLogin.CHECKIN_URL)
                .post(formBody)
        )
        val html = response.body?.string() ?: throw RuntimeException("查询签到记录失败")
        response.close()
        if (XJTULogin.isAuthFailureResponse(html)) {
            throw AuthExpiredException("快速考勤流水")
        }

        return try {
            val doc = Jsoup.parse(html, IclassfaceLogin.BASE_URL)
            doc.select("table tbody tr").mapNotNull { tr ->
                val cells = tr.select("td").map { it.text().trim() }
                // <序号, 姓名, 学号, 时间, 地点, 类型>
                if (cells.size < 6) return@mapNotNull null
                CheckinRecord(
                    name = cells[1],
                    studentNo = cells[2],
                    time = cells[3],
                    location = cells[4],
                    type = cells[5]
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchRecords parse error", e)
            emptyList()
        }
    }
}
