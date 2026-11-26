package com.xjtu.toolbox.superapp

import com.xjtu.toolbox.auth.Jwt
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 移动交大的一项服务。[url] 为空的是「专区」，点开看 [children]。
 * 地址原样保留：子服务不带 App 的登录态，由网页自己走统一认证和回调。
 */
data class SuperAppService(
    val id: String,
    val name: String,
    val url: String,
    val icon: String?,
    val department: String = "",
    val description: String = "",
    val contact: String = "",
    val pinyin: String = "",
    val clicks: Int = 0,
    /** 学校标成只能校内访问（`needLocalNetWork`）：校外要经 WebVPN 打开。 */
    val campusOnly: Boolean = false,
    val children: List<SuperAppService> = emptyList(),
) {
    val isZone: Boolean get() = children.isNotEmpty()

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val compact = q.replace(" ", "").lowercase()
        return name.contains(q, ignoreCase = true) || department.contains(q, ignoreCase = true) ||
            pinyin.lowercase().contains(compact)
    }
}

data class SuperAppCategory(val name: String, val services: List<SuperAppService>)

/** 移动交大的服务目录：一网通办 `portal-api`，认 CAS 签发的 `x-id-token`（由 [com.xjtu.toolbox.auth.YwtbSession] 注入）。 */
class SuperAppApi(private val site: SiteSession) {

    /** 原始响应：调用方原样缓存，下次打开先解析缓存。 */
    suspend fun fetchRaw(): String {
        val request = Request.Builder()
            .url(LIST_URL)
            .header("Accept", "application/json")
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("x-terminal-info", "h5")
            .apply {
                // 官方 H5 带学号；就是 token 里的 sub
                site.localToken["id_token"]?.let { Jwt.claim(it, "sub") }?.let { header("x-user-name", it) }
            }
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val response = site.executeWithReAuth(request)
        return withContext(Dispatchers.IO) {
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IOException("服务列表加载失败（HTTP ${it.code}）")
                body
            }
        }
    }

    companion object {
        /** DataCache 里原始响应的键，服务页和小岱 open_page 共用。 */
        const val CACHE_KEY = "superapp_services"
        private const val ORIGIN = "https://superapp.xjtu.edu.cn"
        private const val LIST_URL = "https://ywtb.xjtu.edu.cn/portal-api/v1/service/list"

        /** `{code, message, data: [{label, services: [...]}]}`；`code` 非 0 抛出服务端那句话。 */
        fun parse(raw: String): List<SuperAppCategory> {
            val root = AppJson.parseToJsonElement(raw).jsonObject
            if (root.int("code") != 0) throw IOException(root.str("message").ifBlank { "服务列表加载失败" })
            val groups = root["data"] as? JsonArray ?: return emptyList()
            return groups.mapNotNull { g ->
                val group = g as? JsonObject ?: return@mapNotNull null
                val services = (group["services"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toService() }
                SuperAppCategory(group.str("label"), services).takeIf { it.name.isNotBlank() && services.isNotEmpty() }
            }
        }

        private fun JsonObject.toService(): SuperAppService? {
            val name = str("serviceName")
            val children = ((get("aggregationServices") as? JsonObject)?.get("aggregationServiceList") as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.toChild() }
                .distinctBy { it.url }
            val url = str("serviceUrl").trim()
            // 既没有地址也没有子服务的条目点了也去不了哪儿
            if (name.isBlank() || (url.isEmpty() && children.isEmpty())) return null
            return SuperAppService(
                id = str("id").ifBlank { name },
                name = name,
                url = url,
                icon = str("iconUrl").ifBlank { str("servicePicUrl") }.ifBlank { null },
                department = str("serviceDepartmentName").ifBlank { str("serviceSource") },
                description = str("serviceDesc").trim(),
                contact = str("contactInformation").trim(),
                pinyin = str("servicePinYin"),
                clicks = int("clickNum") ?: 0,
                campusOnly = bool("needLocalNetWork"),
                children = if (url.isEmpty()) children else emptyList(),
            )
        }

        /** 专区里的子服务字段是下划线命名，只有名称、地址和图标。 */
        private fun JsonObject.toChild(): SuperAppService? {
            val name = str("service_name")
            val url = str("service_url").trim()
            if (name.isBlank() || url.isEmpty()) return null
            return SuperAppService(
                id = str("serviceId").ifBlank { str("id") }.ifBlank { url },
                name = name,
                url = url,
                icon = str("service_icon").ifBlank { null },
                campusOnly = bool("needLocalNetWork"),
            )
        }

        private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

        private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    }
}
