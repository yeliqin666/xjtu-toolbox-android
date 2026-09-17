package com.xjtu.toolbox.newattendance

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.net.URLEncoder

internal object KqHttp {
    private const val TAG = "KqHttp"

    fun execute(site: SiteSession, request: Request, path: String, retryable: Boolean = true): JsonObject {
        var lastError: Exception? = null
        val attempts = if (retryable) 3 else 1
        repeat(attempts) { attempt ->
            if (attempt > 0) Thread.sleep(if (attempt == 1) 600L else 1500L)
            try {
                val (code, body) = runBlocking { site.executeWithReAuth(request) }.use { resp ->
                    resp.code to (resp.body?.string() ?: "")
                }
                if (code in 500..599 || body.isBlank()) {
                    lastError = RuntimeException("考勤系统响应异常 (HTTP $code)")
                    return@repeat
                }
                val json = body.safeParseJsonObject()
                val apiCode = json.get("code")?.takeIf { !it.isJsonNull }?.let {
                    try { it.asInt } catch (_: Exception) { null }
                }
                // executeWithReAuth 已按站点规则处理过期并重放；这里只兜底 peek 漏掉的 4001。
                if (apiCode == 4001) throw AuthExpiredException(site.siteName)
                if (apiCode != null && apiCode != 0) {
                    val msg = json.get("message")?.takeIf { !it.isJsonNull }?.asString
                        ?.ifBlank { null } ?: "考勤接口返回 code=$apiCode"
                    throw RuntimeException(msg)
                }
                if (code in 200..299) return json
                throw RuntimeException("考勤接口请求失败 (HTTP $code)")
            } catch (e: AuthExpiredException) {
                throw e
            } catch (e: java.io.IOException) {
                Log.w(TAG, "REQUEST $path attempt ${attempt + 1} failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("考勤系统请求失败")
    }

    /** 当前会话所属考勤站的根地址。本科和研究生是两套域名，不能写死。 */
    fun baseOf(site: SiteSession): String =
        (site as? com.xjtu.toolbox.auth.NewAttendanceSession)?.baseUrl()
            ?: NewAttendanceLogin.BASE_URL

    fun buildUrl(site: SiteSession, path: String, query: Map<String, String> = emptyMap()): String {
        val base = baseOf(site) + path
        val plain = if (query.isEmpty()) base else {
            val q = query.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            "$base?$q"
        }
        // 基址存的是原始域名；校外要经网关才够得着，在这里统一改写。
        // 漏掉这一步的话登录走了网关、业务请求还在直连，照样连不上。
        return if (site.currentAccessMode == com.xjtu.toolbox.auth.AccessMode.WEBVPN) {
            com.xjtu.toolbox.util.WebVpnUtil.getVpnUrl(plain)
        } else {
            plain
        }
    }

    fun first(obj: JsonObject, vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { !it.isJsonNull }
        }

    fun str(obj: JsonObject, vararg keys: String): String {
        val el = first(obj, *keys) ?: return ""
        return try { el.asString } catch (_: Exception) { "" }
    }

    fun int(obj: JsonObject, vararg keys: String): Int {
        val el = first(obj, *keys) ?: return 0
        return try { el.asInt } catch (_: Exception) {
            el.asString.trim().toDoubleOrNull()?.toInt() ?: 0
        }
    }

    fun long(obj: JsonObject, vararg keys: String): Long {
        val el = first(obj, *keys) ?: return 0L
        return try { el.asLong } catch (_: Exception) {
            el.asString.trim().toDoubleOrNull()?.toLong() ?: 0L
        }
    }

    fun bool(obj: JsonObject, vararg keys: String): Boolean {
        val el = first(obj, *keys) ?: return false
        return try { el.asBoolean } catch (_: Exception) {
            el.asString.trim().lowercase() in setOf("1", "true", "yes", "y")
        }
    }

    fun obj(el: JsonElement?): JsonObject? =
        if (el != null && !el.isJsonNull && el.isJsonObject) el.asJsonObject else null

    fun rows(el: JsonElement?): List<JsonObject> {
        if (el == null || el.isJsonNull) return emptyList()
        if (el.isJsonArray) {
            return el.asJsonArray.mapNotNull { obj(it) }
        }
        val o = obj(el) ?: return emptyList()
        for (key in listOf("rows", "records", "list", "items", "content")) {
            val child = o.get(key)
            if (child != null && child.isJsonArray) {
                return child.asJsonArray.mapNotNull { obj(it) }
            }
        }
        return emptyList()
    }

    fun dataObject(root: JsonObject): JsonObject = obj(root.get("data")) ?: root

    fun pagePayload(data: JsonObject, pageNum: Int = 1, pageSize: Int = 500): JsonObject =
        JsonObject().apply {
            addProperty("pageNum", pageNum.coerceAtLeast(1))
            addProperty("pageSize", pageSize.coerceIn(1, 500))
            add("data", data)
        }
}
