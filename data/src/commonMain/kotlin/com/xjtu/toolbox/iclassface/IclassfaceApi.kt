package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.util.todayInSystemZone
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.platform.Log
import kotlinx.datetime.LocalDate
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 人脸识别签到查询 API（iclassface.xjtu.edu.cn）
 *
 * 只做「今天刷没刷上卡」这一件事：POST /checkin/records 提交日期，解析返回的
 * 服务端渲染 HTML 表格。不涉及 /wifi/location（教室定位，依赖真实校园网 WiFi
 * 环境，客户端无法稳定复刻，详见 PLAN.md）。
 *
 * ## 它为什么搬进 `:data`
 *
 * 从 `:app` 的 `iclassface/IclassfaceApi.kt` 搬进 `:data`（桌面端第 20 条真数据路由：桌面要自己登
 * `iclassface`、自己查记录）。类名与包路径一字未改，三处跟着走：
 *
 *  - `CheckinRecord` 搬到了 **:core** 的 `iclassface/IclassfaceSource.kt`（它是屏要画的模型；
 *    同名同字段，只是从嵌套类变成顶层类）—— 本文件与 `:core` 的屏在同一个包名下，不需要 import；
 *  - `java.time.LocalDate` → `kotlinx.datetime.LocalDate`（`:data` 只有 jvm/android 两个目标，
 *    两种都编得过；收共享层的类型是为了让端口签名两端一致。`toString()` 同为 ISO `yyyy-MM-dd`，
 *    所以打出去的 `date=` 一个字没变）；
 *  - `android.util.Log` → `:core` 的 `Log`（tag 与级别不变）。
 *
 * 会话失效抛的是 **`SessionExpiredException`**（`:app` 的 `AuthExpiredException` 的子类
 * `SessionExpiredFailure` 在 `:core`）：搬迁前这里抛 `AuthExpiredException`，那时它还在 `:app`。
 * 两端认的都是同一个 `SessionExpiredFailure`，所以屏上那条走向逐字相同。
 */
class IclassfaceApi(private val site: SiteSession) {

    companion object {
        private const val TAG = "IclassfaceApi"
    }

    private suspend fun execute(builder: Request.Builder) =
        site.executeWithReAuth(builder.build())

    /**
     * 查询指定日期的签到记录（默认今天）。
     * 返回空列表表示当天确实没有任何记录（含"还没刷上卡"的情况）。
     */
    suspend fun fetchRecords(date: LocalDate = todayInSystemZone()): List<CheckinRecord> {
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
            throw SessionExpiredException("快速考勤流水")
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

