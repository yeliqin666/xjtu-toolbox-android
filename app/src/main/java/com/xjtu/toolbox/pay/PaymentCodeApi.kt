package com.xjtu.toolbox.pay

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * 付款码 API — ncard.xjtu.edu.cn
 *
 * 学校在 2025-12 切换到新版 e校园卡（慧新E校）：
 * 旧的 pay.xjtu.edu.cn/ThirdWeb/GetBarCode 已下线，新接口走
 * /berserker-app/authCode/batchGetBarCodeGet，使用与校园卡同一 JWT。
 *
 * 关键 header：
 *   - synjones-auth: bearer <JWT>  （由 CampusCardLogin 登录后持有）
 *   - synAccessSource: h5
 *   - Referer: https://ncard.xjtu.edu.cn/plat/pay?lite=1&payacc=000&payid=0
 */
class PaymentCodeApi(private val site: SiteSession) {

    companion object {
        private const val TAG = "PaymentCodeApi"
        private const val BASE_URL = "https://ncard.xjtu.edu.cn"
        private const val BAR_CODE_URL =
            "$BASE_URL/berserker-app/authCode/batchGetBarCodeGet?payacc=000&voucherType=1&synAccessSource=h5"
        private const val REFERER = "$BASE_URL/plat/pay?lite=1&payacc=000&payid=0"

        fun clearCachedJwt() { /* noop - JWT lifecycle is owned by SiteSession */ }
    }

    /**
     * 兼容旧接口：在新流程中无需独立认证，JWT 直接从 CampusCardLogin 取。
     * 若 CampusCardLogin 尚未持有 token，则尝试 reAuthenticate 一次。
     */
    fun authenticate() {
        if (!site.localToken["access_token"].isNullOrEmpty()) return
        throw RuntimeException("校园卡未登录，无法获取付款码")
    }

    /**
     * 获取付款码数字。
     * @return 付款码数字字符串（如 "40806400076085649835"）
     */
    fun getBarCode(): String {
        val token = site.localToken["access_token"]
            ?: throw RuntimeException("校园卡未登录，无法获取付款码")

        val request = Request.Builder()
            .url(BAR_CODE_URL)
            .header("synjones-auth", "bearer $token")
            .header("synAccessSource", "h5")
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .get()
            .build()

        val resp = runBlocking { site.executeWithReAuth(request) }
        val text = resp.body?.use { it.string() } ?: throw RuntimeException("空响应")
        Log.d(TAG, "getBarCode: code=${resp.code}, body=${text.take(200)}")

        val root = text.safeParseJsonObject()
        if (root.get("success")?.asBoolean != true) {
            val msg = root.get("msg")?.asString ?: text.take(100)
            throw RuntimeException("获取付款码失败：$msg")
        }
        val data = root.getAsJsonObject("data")
            ?: throw RuntimeException("响应缺少 data 字段")
        val barCodeVo = data.getAsJsonObject("barCodeVo")
            ?: throw RuntimeException("响应缺少 barCodeVo 字段")
        val arr = barCodeVo.getAsJsonArray("barcode")
        if (arr == null || arr.size() == 0) {
            throw RuntimeException("付款码数组为空")
        }
        return arr[0].asString
    }
}
