package com.xjtu.toolbox.pay

import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 付款码 API — pay.xjtu.edu.cn
 * CAS OAuth 认证 → 从 HTML 提取 JWT → GetBarCode 获取动态付款码数字。
 */
class PaymentCodeApi(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "PaymentCodeApi"
        private const val BASE_URL = "https://pay.xjtu.edu.cn"
        private const val CAS_ENTRY = "$BASE_URL/ThirdWeb/CasQrcode"
        private val JWT_REGEX = Regex("""sessionStorage\.Authorization\s*=\s*'(eyJ[^']+)'""")

        /** JWT 全局缓存（同一进程内共享，避免重复认证） */
        @Volatile private var cachedJwt: String? = null
        @Volatile private var cachedJwtTime: Long = 0
        private const val JWT_TTL_MS = 30 * 60 * 1000L  // 30分钟有效

        /** 清除缓存的 JWT（logout 时调用） */
        fun clearCachedJwt() {
            cachedJwt = null
            cachedJwtTime = 0
        }
    }

    /** JWT 认证令牌（从 CasQrcode 页面提取） */
    private var jwtToken: String? = null

    /**
     * CAS OAuth 认证并提取 JWT。
     * 优先使用全局缓存（30分钟内），避免重复走 CAS 重定向链。
     */
    fun authenticate() {
        // 尝试使用缓存的 JWT
        val cached = cachedJwt
        if (cached != null && System.currentTimeMillis() - cachedJwtTime < JWT_TTL_MS) {
            Log.d(TAG, "authenticate: using cached JWT (age=${(System.currentTimeMillis() - cachedJwtTime) / 1000}s)")
            jwtToken = cached
            return
        }

        Log.d(TAG, "authenticate: visiting $CAS_ENTRY")
        val resp = client.newCall(
            Request.Builder().url(CAS_ENTRY).get().build()
        ).execute()
        val body = resp.body?.use { it.string() } ?: ""
        val finalUrl = resp.request.url.toString()
        Log.d(TAG, "authenticate: code=${resp.code}, finalUrl=$finalUrl, bodyLen=${body.length}")

        if (resp.code != 200 || !finalUrl.contains("pay.xjtu.edu.cn")) {
            throw RuntimeException("付款码认证失败 (code=${resp.code}, url=$finalUrl)")
        }

        jwtToken = JWT_REGEX.find(body)?.groupValues?.get(1)
            ?: throw RuntimeException("未找到 JWT 令牌")
        // 更新全局缓存
        cachedJwt = jwtToken
        cachedJwtTime = System.currentTimeMillis()
        Log.d(TAG, "authenticate: JWT extracted and cached (len=${jwtToken!!.length})")
    }

    /**
     * 获取付款码数字。
     * @return 付款码数字字符串（如 "40429438268984523523"）
     */
    fun getBarCode(): String {
        val token = jwtToken ?: throw RuntimeException("未认证，请先调用 authenticate()")

        val body = FormBody.Builder()
            .add("acctype", "000")
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/ThirdWeb/GetBarCode")
            .post(body)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Authorization", token)
            .header("Referer", CAS_ENTRY)
            .build()

        val resp = client.newCall(request).execute()
        val text = resp.body?.string() ?: throw RuntimeException("空响应")
        Log.d(TAG, "getBarCode: code=${resp.code}, body=${text.take(200)}")

        val root = text.safeParseJsonObject()
        if (root.get("IsSucceed")?.asBoolean != true) {
            throw RuntimeException("获取付款码失败: ${root.get("Msg")?.asString ?: text.take(100)}")
        }
        val arr = root.getAsJsonArray("Obj")
        if (arr == null || arr.size() == 0) {
            throw RuntimeException("付款码数组为空")
        }
        return arr[0].asString
    }
}
