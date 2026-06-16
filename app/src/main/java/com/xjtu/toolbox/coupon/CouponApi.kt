package com.xjtu.toolbox.coupon

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CouponApi(private val site: SiteSession) {
    companion object {
        private const val BASE_URL = "https://egc.xjtu.edu.cn"
        private const val RECEIVE_URL = "$BASE_URL/page/cas/receiveCas.html?version=SAFT_VERSION"
        private const val TYPE_LIST_URL = "$BASE_URL/app/voucher/query.type.list"
        private const val AUTO_SWITCH_URL = "$BASE_URL/app/voucher/get.auto.receive.switch"
        private const val PAGE_LIST_URL = "$BASE_URL/app/voucher/query.page.list"
        private const val DETAIL_URL = "$BASE_URL/app/voucher/query.details"
        private const val ACTIVATE_URL = "$BASE_URL/app/voucher/activate"
        private const val ACTIVATE_PERCENT_URL = "$BASE_URL/app/voucher/get.activate.percent"
        private val JSON = "application/json;charset=UTF-8".toMediaType()
    }

    fun getAutoReceiveSwitch(): String {
        val root = executeVoucherJson(AUTO_SWITCH_URL, "", allowRetry = true)
        return root.safeGet("data").safeString()
    }

    fun getCouponTypes(): List<CouponType> {
        val root = executeVoucherJson(TYPE_LIST_URL, """{"json":true}""", allowRetry = true)
        return CouponJsonParser.parseTypes(root)
    }

    fun queryCoupons(
        filter: CouponFilter,
        page: Int = 1,
        pageSize: Int = 20
    ): CouponPage {
        val body = """
            {
              "pageNum": $page,
              "pageSize": $pageSize,
              "obj": {
                "typeId": 4,
                "status": "${filter.status}",
                "count": "${filter.count}",
                "expired": "${filter.expired}"
              },
              "json": true
            }
        """.trimIndent()
        val root = executeVoucherJson(PAGE_LIST_URL, body, allowRetry = true)
        return CouponJsonParser.parsePage(root)
    }

    fun getCouponDetail(showCardId: String): CouponDetail {
        val body = """{"cardId":"$showCardId","json":true}"""
        val root = executeVoucherJson(DETAIL_URL, body, allowRetry = true)
        return CouponJsonParser.parseDetail(root, showCardId)
    }

    fun activateCoupon(showCardId: String) {
        val body = """{"cardId":"$showCardId","json":true}"""
        executeVoucherJson(ACTIVATE_URL, body, allowRetry = true)
    }

    fun getActivatePercent(batchId: String): String {
        if (batchId.isBlank()) return ""
        val body = """{"batchId":"$batchId","json":true}"""
        val root = executeVoucherJson(ACTIVATE_PERCENT_URL, body, allowRetry = true)
        return root.safeGet("data").safeString()
    }

    private fun executeVoucherJson(url: String, jsonBody: String, allowRetry: Boolean): JsonObject {
        val text = executeRaw(url, jsonBody, allowRetry)
        if (text.isBlank()) throw RuntimeException("服务器返回空数据")
        if (text.contains("<html", ignoreCase = true)) {
            throw AuthExpiredException("加餐券")
        }

        val root = try {
            text.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("加餐券返回了非JSON数据: ${text.take(80)}")
        }

        val code = root.safeGet("code")?.asIntOrNull()
        if (code != null && code != 200) {
            val msg = root.safeGet("msg").safeString("加餐券接口返回错误: $code")
            if (code == 401 || code == 403 || msg.contains("令牌")) throw AuthExpiredException("加餐券")
            throw RuntimeException(msg)
        }
        return root
    }

    private fun executeRaw(url: String, jsonBody: String, allowRetry: Boolean): String {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Origin", BASE_URL)
            .header("Referer", RECEIVE_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val response = runBlocking { site.executeWithReAuth(request) }
        return response.use {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("加餐券接口请求失败: HTTP ${response.code}")
            text
        }
    }
}

private fun JsonElement.asIntOrNull(): Int? = try {
    asInt
} catch (_: Exception) {
    null
}
