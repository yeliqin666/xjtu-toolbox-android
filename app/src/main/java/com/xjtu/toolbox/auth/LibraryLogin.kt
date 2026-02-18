package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 图书馆座位预约系统登录
 *
 * 认证链路：
 * 直接对 rg.lib.xjtu.edu.cn:8086/seat/ 发起 CAS SSO 认证。
 * 座位系统本身是 CAS 保护的服务，访问时会 302 到 login.xjtu.edu.cn，
 * 如果已有 TGC cookie（已登录其它服务），CAS 自动签发 ticket 回跳。
 *
 * ⚠️ 不再走 www.lib.xjtu.edu.cn 门户（那是 Vue SPA，没有 CAS 表单）
 */
class LibraryLogin(
    existingClient: OkHttpClient? = null,
    visitorId: String? = null
) : XJTULogin(
    // 直接认证座位系统——它本身是 CAS 服务
    loginUrl = "http://rg.lib.xjtu.edu.cn:8086/seat/",
    existingClient = existingClient,
    visitorId = visitorId
) {
    companion object {
        private const val TAG = "LibraryLogin"
        const val SEAT_BASE_URL = "http://rg.lib.xjtu.edu.cn:8086"
    }

    /** 座位系统是否已认证 */
    var seatSystemReady: Boolean = false

    /** 诊断信息（供 UI 展示） */
    var diagnosticInfo: String = ""
        private set

    /** 重新尝试访问座位系统 */
    fun reAuthenticate(): Boolean {
        try {
            val seatRequest = Request.Builder()
                .url("$SEAT_BASE_URL/seat/")
                .get()
                .build()
            val seatResponse = client.newCall(seatRequest).execute()
            val seatBody = seatResponse.body?.use { it.string() } ?: ""
            if (seatBody.contains("btn-group") || seatBody.contains("tab-select") || seatBody.contains("seat")) {
                seatSystemReady = true
                diagnosticInfo = "座位系统已就绪"
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
            diagnosticInfo = "重新认证失败: ${e.message}"
        }
        return false
    }

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        val body = lastResponseBody  // body 已在 XJTULogin 中读取并存储
        Log.d(TAG, "postLogin: finalUrl=$finalUrl, bodyLen=${body.length}")

        // 因为 loginUrl 就是座位系统，init 成功后 response 已经是座位页面
        if (finalUrl.contains("rg.lib.xjtu.edu.cn") && !finalUrl.contains("login.xjtu.edu.cn")) {
            // 检查是否拿到了座位页面内容
            if (body.contains("btn-group") || body.contains("tab-select") || body.contains("seat")) {
                seatSystemReady = true
                diagnosticInfo = "座位系统已就绪"
                Log.d(TAG, "postLogin: Seat system ready (direct CAS auth succeeded)")
                return
            }
        }

        // 如果 init 返回的不是座位页面（比如 CAS 发了 ticket 但没跟踪到最终页面），
        // 再显式访问一次座位系统
        Log.d(TAG, "postLogin: init response not seat page, retrying explicit access...")
        try {
            val seatRequest = Request.Builder()
                .url("$SEAT_BASE_URL/seat/")
                .get()
                .build()
            val seatResponse = client.newCall(seatRequest).execute()
            val seatBody = seatResponse.body?.use { it.string() } ?: ""
            val seatFinalUrl = seatResponse.request.url.toString()

            Log.d(TAG, "postLogin retry: code=${seatResponse.code}, finalUrl=$seatFinalUrl, bodyLen=${seatBody.length}")

            if (seatBody.contains("btn-group") || seatBody.contains("tab-select") || seatBody.contains("seat")) {
                seatSystemReady = true
                diagnosticInfo = "座位系统已就绪"
            } else if (seatFinalUrl.contains("login.xjtu.edu.cn")) {
                diagnosticInfo = "CAS 认证未完成，请确认已连接校园网或 VPN"
            } else {
                diagnosticInfo = "座位系统返回异常页面\n标题: ${extractTitle(seatBody)}\nURL: $seatFinalUrl"
            }
        } catch (e: Exception) {
            Log.e(TAG, "postLogin retry failed", e)
            diagnosticInfo = "座位系统访问失败: ${e.message}"
        }
    }

    private fun extractTitle(html: String): String {
        val match = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1) ?: "(无标题)"
    }
}