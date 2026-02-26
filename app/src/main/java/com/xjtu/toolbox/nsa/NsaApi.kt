package com.xjtu.toolbox.nsa

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

// ── 数据类 ──────────────────────────────

/**
 * 学生个人信息（来自 getGrkpInfo + commonGrxx + getUserRoleInfo 合并）
 *
 * [details] 为有序列表，存储"标签→值"展示对，用于 UI 遍历。
 * 首次登录后持久化到 CredentialStore，后续冷启动直接从缓存恢复。
 */
data class NsaStudentProfile(
    val name: String,          // 姓名
    val studentId: String,     // 学号
    val college: String,       // 学院
    val major: String,         // 专业（保留数字前缀）
    val details: List<Pair<String, String>> = emptyList()  // 有序 (label, value)
) {
    /** 序列化为 JSON 字符串（持久化用） */
    fun toJson(): String {
        val obj = JsonObject()
        obj.addProperty("name", name)
        obj.addProperty("studentId", studentId)
        obj.addProperty("college", college)
        obj.addProperty("major", major)
        val arr = com.google.gson.JsonArray()
        details.forEach { (k, v) ->
            val pair = JsonObject()
            pair.addProperty("k", k)
            pair.addProperty("v", v)
            arr.add(pair)
        }
        obj.add("details", arr)
        return obj.toString()
    }

    companion object {
        /** 从 JSON 字符串反序列化 */
        fun fromJson(json: String): NsaStudentProfile? = try {
            val obj = JsonParser.parseString(json).asJsonObject
            val detailsList = mutableListOf<Pair<String, String>>()
            obj.getAsJsonArray("details")?.forEach { el ->
                val p = el.asJsonObject
                detailsList.add(p.get("k").asString to p.get("v").asString)
            }
            NsaStudentProfile(
                name = obj.get("name").asString,
                studentId = obj.get("studentId").asString,
                college = obj.get("college").asString,
                major = obj.get("major").asString,
                details = detailsList
            )
        } catch (e: Exception) {
            Log.w("NsaApi", "fromJson failed: ${e.message}"); null
        }
    }
}

// ── API 实现 ─────────────────────────────

/**
 * 学工系统 (nsa.xjtu.edu.cn) API
 *
 * ### 认证架构
 * NSA 使用 **OAuth2** 认证（非直接 CAS ServiceTicket）：
 * ```
 * pd.zf → org.xjtu.edu.cn/oauth → CAS OAuth2 → ST → callbackAuthorize → getUserInfoByAccessToken.zf → session
 * ```
 * 需要 CAS TGC cookie 有效；TGC 失效时通过 [casRefresher] 回调重新认证。
 *
 * @param client       共享 OkHttpClient（携带 PersistentCookieJar，含 CAS TGC）
 * @param casRefresher 刷新 CAS TGC 的回调（调用 jwxtLogin.reAuthenticate()），返回 true 表示成功
 */
class NsaApi(
    private val client: OkHttpClient,
    private val casRefresher: (() -> Boolean)? = null
) {

    companion object {
        private const val TAG = "NsaApi"
        private const val BASE = "https://nsa.xjtu.edu.cn/zftal-xgxt-web"

        // ── 会话缓存（进程级单例，跨 NsaApi 实例共享） ──
        @Volatile private var sessionValid = false
        @Volatile private var cachedStudentId: String? = null
        @Volatile private var cachedStudentName: String? = null
        @Volatile private var cachedRoleDm: String? = null
        private val sessionLock = Any()

        /** 登出时清除会话缓存 */
        fun clearSession() {
            synchronized(sessionLock) {
                sessionValid = false
                cachedStudentId = null
                cachedStudentName = null
                cachedRoleDm = null
            }
        }
    }

    // ══════════════════════════════════════
    // 会话管理
    // ══════════════════════════════════════

    /**
     * 建立 NSA 会话（OAuth2 全链路）
     *
     * 1. 先快速检查现有 cookie 是否仍有效
     * 2. 从 pd.zf 获取 OAuth 入口 URL（无需会话）
     * 3. 跟随 OAuth 重定向链（OkHttpClient 自动 followRedirects）
     * 4. 若 CAS TGC 失效（链条停在登录页），调用 casRefresher 刷新后重试
     * 5. 切换到学生角色（switchRole）
     *
     * @return true = 会话建立成功
     */
    fun ensureSession(): Boolean = synchronized(sessionLock) {
        if (sessionValid) {
            Log.d(TAG, "ensureSession: cached session valid (id=$cachedStudentId)")
            return true
        }

        // Step 1: 快速检查现有 cookie
        if (checkSession()) {
            Log.d(TAG, "ensureSession: existing cookies valid")
            switchRole()
            sessionValid = true
            return true
        }

        // Step 2: 获取 OAuth 入口 URL
        val oauthUrl = getOAuthUrl()
        if (oauthUrl == null) {
            Log.e(TAG, "ensureSession: pd.zf did not return OAuth URL")
            return false
        }
        Log.d(TAG, "ensureSession: oauthUrl=${oauthUrl.take(120)}...")

        // Step 3: 跟随 OAuth 重定向链
        if (followOAuthAndVerify(oauthUrl)) {
            switchRole()
            sessionValid = true
            Log.d(TAG, "ensureSession: OAuth succeeded (id=$cachedStudentId)")
            return true
        }

        // Step 4: TGC 过期，尝试刷新
        if (casRefresher != null) {
            Log.d(TAG, "ensureSession: refreshing CAS TGC...")
            val ok = try { casRefresher.invoke() } catch (e: Exception) {
                Log.w(TAG, "casRefresher failed", e); false
            }
            if (ok) {
                Log.d(TAG, "ensureSession: TGC refreshed, retrying OAuth...")
                if (followOAuthAndVerify(oauthUrl)) {
                    switchRole()
                    sessionValid = true
                    Log.d(TAG, "ensureSession: OAuth succeeded after TGC refresh")
                    return true
                }
            }
        }

        Log.e(TAG, "ensureSession: all attempts failed")
        return false
    }

    // ── OAuth 内部方法 ──

    /** pd.zf 返回 OAuth 登录重定向 URL（不需要会话），仅替换首个 http→https */
    private fun getOAuthUrl(): String? = try {
        val resp = client.newCall(Request.Builder().url("$BASE/teacher/xtgl/index/pd.zf").get().build()).execute()
        val json = resp.body?.string().safeParseJsonObject()
        json.getAsJsonObject("data")?.get("rzdldz")?.asString
            ?.replaceFirst("http://", "https://")   // 仅替换主 URL 协议，保留 redirectUri 中的 http://
    } catch (e: Exception) {
        Log.e(TAG, "getOAuthUrl failed", e); null
    }

    /**
     * 跟随 OAuth 全链路并验证会话
     *
     * OkHttpClient（followRedirects + followSslRedirects）自动处理 ~8 次 302：
     * org.xjtu.edu.cn → CAS OAuth2 → CAS login (auto-ST if TGC valid) → callback → NSA session
     *
     * 如果 TGC 有效，最终 URL 落在 nsa.xjtu.edu.cn（会话已建立）；
     * 如果 TGC 无效，最终 URL 停在 login.xjtu.edu.cn/cas/login（登录页 200）。
     */
    private fun followOAuthAndVerify(oauthUrl: String): Boolean = try {
        val resp = client.newCall(Request.Builder().url(oauthUrl).get().build()).execute()
        val finalUrl = resp.request.url.toString()
        resp.body?.string()  // 消费 body

        Log.d(TAG, "followOAuth: finalUrl=${finalUrl.take(120)}")
        if (finalUrl.contains("login.xjtu.edu.cn/cas/login")) {
            Log.w(TAG, "followOAuth: stopped at CAS login page → TGC invalid")
            false
        } else {
            checkSession()
        }
    } catch (e: Exception) {
        Log.e(TAG, "followOAuth failed", e); false
    }

    /** getUserRoleInfo 检查会话 + 缓存学号/姓名/角色 */
    private fun checkSession(): Boolean = try {
        val resp = client.newCall(
            Request.Builder().url("$BASE/teacher/xtgl/index/getUserRoleInfo.zf").get().build()
        ).execute()
        val json = resp.body?.string().safeParseJsonObject()
        if (json.get("code")?.asInt == 0) {
            val data = json.getAsJsonObject("data")
            cachedStudentId = data?.get("zgh")?.asString
            cachedStudentName = data?.get("xm")?.asString?.trim()
            cachedRoleDm = data?.get("mrjsdm")?.asString
            Log.d(TAG, "checkSession: OK (id=$cachedStudentId, name=$cachedStudentName)")
            true
        } else {
            Log.d(TAG, "checkSession: not auth (code=${json.get("code")})")
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "checkSession failed", e); false
    }

    /** 切换到学生角色（部分 API 需要正确角色才返回数据） */
    private fun switchRole() {
        val dm = cachedRoleDm ?: return
        try {
            val body = FormBody.Builder().add("jsdm", dm).build()
            val resp = client.newCall(
                Request.Builder().url("$BASE/teacher/xtgl/login/switchRole.zf").post(body).build()
            ).execute()
            resp.body?.string()  // 消费
            Log.d(TAG, "switchRole: done")
        } catch (e: Exception) {
            Log.w(TAG, "switchRole failed (non-fatal)", e)
        }
    }

    // ══════════════════════════════════════
    // 公开 API
    // ══════════════════════════════════════

    /** 会话建立后缓存的学号 */
    val studentId: String? get() = cachedStudentId

    /** 会话建立后缓存的姓名 */
    val studentName: String? get() = cachedStudentName

    /**
     * 完整个人信息（合并 getGrkpInfo + commonGrxx）
     *
     * - 基本字段（姓名/学号/学院/专业）直接取 getGrkpInfo.zf
     * - 详情字段（性别/民族/政治面貌/年级/班级/辅导员/宿舍等）取 commonGrxx.zf
     * - 专业名**保留**前缀数字编号（如 "0940物理学（国家拔尖计划）"）
     */
    fun getProfile(): NsaStudentProfile? {
        if (!ensureSession()) return null

        // ── getGrkpInfo: 基本字段 ──
        var name = cachedStudentName ?: ""
        var studentId = cachedStudentId ?: ""
        var college = ""
        var major = ""
        try {
            val body = client.newCall(Request.Builder().url("$BASE/teacher/xtgl/index/getGrkpInfo.zf").get().build())
                .execute().use { it.body?.string() }
            Log.d(TAG, "getGrkpInfo: ${body?.take(500)}")
            val json = body.safeParseJsonObject()
            if (json.get("code")?.asInt == 0) {
                val data = json.getAsJsonObject("data")
                if (data != null) {
                    name = data.get("xm").safeString().trim().ifEmpty { name }
                    studentId = data.get("zgh").safeString().ifEmpty { studentId }
                    college = data.get("bmmc").safeString()
                    major = data.get("zymc").safeString()   // 不去除数字前缀
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getGrkpInfo failed: ${e.message}")
        }

        if (name.isEmpty() && studentId.isEmpty()) return null
        return NsaStudentProfile(
            name = name,
            studentId = studentId,
            college = college,
            major = major
            // details 由 getPersonalDetails() 单独获取后合并
        )
    }

    // ── 展示字段白名单（fieldCode → 展示顺序） ──
    // 只展示这些字段，跳过敏感/冗余/空白字段
    private val userInfoFields = linkedMapOf(
        "xbdm"   to "性别",
        "csrq"   to "出生日期",
        "mzdm"   to "民族",
        "zzmmdm" to "政治面貌",
        "xxdm"   to "血型",
        "jgdm"   to "籍贯",
        "sg"     to "身高",
        "tz"     to "体重"
    )
    private val zxxxFields = linkedMapOf(
        "nj"     to "年级",
        "pycc"   to "培养层次",
        "sydm"   to "书院",
        "xqdm"   to "校区",
        "rxrq"   to "入学时间",
        "xz"     to "学制",
        "qsh"    to "寝室号",
        "xjztdm" to "学籍状态"
    )

    /**
     * 获取详细个人信息（从 dynamic/form/group API）
     *
     * 调用两个端点：
     * - `userInfo/default.zf?dataId=null` → 基本个人信息（性别/出生日期/民族等）
     * - `zxxx/default.zf?dataId=null`     → 在校信息（年级/书院/校区/寝室等）
     *
     * 每个字段的 `options` 数组包含 code→label 映射，用于将编码值解析为可读文本。
     *
     * @return 有序 (标签, 值) 列表，为空表示获取失败
     */
    fun getPersonalDetails(): List<Pair<String, String>> {
        if (!ensureSession()) return emptyList()

        val result = mutableListOf<Pair<String, String>>()

        // ── userInfo: 基本个人信息 ──
        try {
            val body = client.newCall(
                Request.Builder().url("$BASE/dynamic/form/group/userInfo/default.zf?dataId=null").get().build()
            ).execute().use { it.body?.string() }
            val json = body.safeParseJsonObject()
            if (json.get("code")?.asInt == 0) {
                val fields = extractFormFields(json)
                for ((code, label) in userInfoFields) {
                    val f = fields[code] ?: continue
                    val value = resolveFieldValue(f)
                    if (value.isNotEmpty()) {
                        // 身高/体重追加单位
                        val display = when (code) {
                            "sg" -> "${value} cm"
                            "tz" -> "${value} kg"
                            else -> value
                        }
                        result.add(label to display)
                    }
                }
            }
            Log.d(TAG, "userInfo: parsed ${result.size} fields")
        } catch (e: Exception) {
            Log.w(TAG, "userInfo failed: ${e.message}")
        }

        // ── zxxx: 在校信息 ──
        val zxxxStart = result.size
        try {
            val body = client.newCall(
                Request.Builder().url("$BASE/dynamic/form/group/zxxx/default.zf?dataId=null").get().build()
            ).execute().use { it.body?.string() }
            val json = body.safeParseJsonObject()
            if (json.get("code")?.asInt == 0) {
                val fields = extractFormFields(json)
                for ((code, label) in zxxxFields) {
                    val f = fields[code] ?: continue
                    val value = resolveFieldValue(f)
                    if (value.isNotEmpty()) {
                        val display = when (code) {
                            "nj" -> "${value}级"
                            "xz" -> "${value}年"
                            else -> value
                        }
                        result.add(label to display)
                    }
                }
            }
            Log.d(TAG, "zxxx: parsed ${result.size - zxxxStart} fields")
        } catch (e: Exception) {
            Log.w(TAG, "zxxx failed: ${e.message}")
        }

        Log.d(TAG, "getPersonalDetails: total ${result.size} fields")
        return result
    }

    /**
     * 从 dynamic/form/group 响应中提取 fieldCode → field JSON 映射
     */
    private fun extractFormFields(json: JsonObject): Map<String, JsonObject> {
        val map = mutableMapOf<String, JsonObject>()
        val data = json.getAsJsonObject("data") ?: return map
        val groupsEl = data.get("groupFields")
        if (groupsEl == null || groupsEl.isJsonNull || !groupsEl.isJsonArray) return map
        for (group in groupsEl.asJsonArray) {
            val go = group.asJsonObject
            val fieldsEl = go.get("fields")
            if (fieldsEl == null || fieldsEl.isJsonNull || !fieldsEl.isJsonArray) continue
            for (field in fieldsEl.asJsonArray) {
                val fo = field.asJsonObject
                val code = fo.get("fieldCode")?.asString ?: continue
                map[code] = fo
            }
        }
        return map
    }

    /**
     * 解析字段值：如果有 options 数组，将编码值映射为可读标签
     *
     * 例如 xbdm="1" + options=[{value:"1", label:"男"}] → "男"
     */
    private fun resolveFieldValue(field: JsonObject): String {
        val raw = field.get("defaultValue")?.let {
            if (it.isJsonNull) return ""
            it.asString.trim()
        } ?: return ""
        if (raw.isEmpty()) return ""

        val optionsEl = field.get("options")
        if (optionsEl != null && !optionsEl.isJsonNull && optionsEl.isJsonArray) {
            val options = optionsEl.asJsonArray
            if (options.size() > 0) {
                for (opt in options) {
                    val o = opt.asJsonObject
                    if (o.get("value")?.asString == raw) {
                        return o.get("label")?.asString ?: raw
                    }
                }
            }
            // 籍贯等大量选项，code 太长可能找不到精确匹配 → 返回原值
        }
        return raw
    }

    /**
     * 学生证照片（JPEG bytes）
     *
     * 服务端可能返回：
     * - 原始 JPEG binary（Content-Type: image/jpeg）
     * - 纯 base64 文本（Content-Type: text/plain 或 application/json）
     * - data URI（data:image/jpeg;base64,...）
     * 全部处理。
     */
    fun getStudentPhoto(): ByteArray? {
        if (!ensureSession()) return null
        val id = cachedStudentId ?: return null
        val url = "$BASE/xsxx/xsxx/xsgl/getXszp.zf?yhm=$id"
        Log.d(TAG, "getStudentPhoto: $url")
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code != 200) return null
                val ct = response.header("Content-Type") ?: ""
                val bytes = response.body?.bytes() ?: return null
                if (bytes.size < 100) return null

                // 二进制图片
                if (ct.contains("image", true) || ct.contains("octet-stream", true)) {
                    Log.d(TAG, "getStudentPhoto: binary ${bytes.size}B")
                    return bytes
                }

                // 文本格式（base64 / data-URI / JSON 错误）
                val text = String(bytes, Charsets.UTF_8).trim()
                when {
                    text.startsWith("/9j/") || text.startsWith("iVBOR") -> {
                        Base64.decode(text, Base64.DEFAULT).also {
                            Log.d(TAG, "getStudentPhoto: base64 → ${it.size}B")
                        }
                    }
                    text.startsWith("data:image") -> {
                        Base64.decode(text.substringAfter(","), Base64.DEFAULT).also {
                            Log.d(TAG, "getStudentPhoto: dataURI → ${it.size}B")
                        }
                    }
                    else -> {
                        Log.w(TAG, "getStudentPhoto: unexpected: ${text.take(200)}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStudentPhoto failed", e); null
        }
    }

    /** 使会话失效（下次 API 调用前重新建立） */
    fun invalidateSession() = synchronized(sessionLock) { sessionValid = false }
}
