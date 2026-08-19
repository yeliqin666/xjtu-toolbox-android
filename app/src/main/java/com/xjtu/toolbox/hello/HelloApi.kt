package com.xjtu.toolbox.hello

import android.util.Log
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * 学生档案。字段取自 `hello.xjtu.edu.cn` 的 `/yingxin/user/afterLogin`。
 *
 * **刻意不收录 `idNumber`（身份证号）。** 接口确实会返回完整身份证号，但本 App
 * 没有任何功能需要它，展示或落盘都只会凭空增加一份敏感数据的泄露面。同理
 * 未收录 `examNumber`。
 *
 * 这个类会被 Gson 序列化进 DataCache，字段名即缓存格式——改名要考虑旧缓存兼容
 * （反序列化时缺失字段会得到 null / 默认值，不会崩，但会丢数据直到下次刷新）。
 */
data class HelloProfile(
    val name: String = "",
    val sno: String = "",
    /** 1=男 2=女，其他值按未知处理 */
    val sex: Int = 0,
    val birthdate: String = "",
    val grade: Int = 0,
    val campusName: String = "",
    /** 书院（本科生特有，与学院并列） */
    val academyName: String = "",
    /** 学院 */
    val departmentName: String = "",
    val professionName: String = "",
    val className: String = "",
    /** 学制年数 */
    val schoolingLen: Int = 0,
    val enterSchoolDate: String = "",
    /** 校园卡号 */
    val cardId: String = "",
    val pictureUrl: String = "",
    val classTeacherName: String = "",
    val classTeacherPhone: String = "",
    val counselorName: String = "",
    val counselorPhone: String = "",
    val counselorOffice: String = "",
    /** 本条数据的抓取时刻，用于"多久前更新"与静默刷新判定 */
    val fetchedAt: Long = 0L,
) {
    val sexLabel: String get() = when (sex) {
        1 -> "男"
        2 -> "女"
        else -> ""
    }

    /** 有没有值得展示的内容——全空时不渲染卡片，避免出现一张空壳。 */
    fun hasContent(): Boolean =
        name.isNotBlank() || departmentName.isNotBlank() || className.isNotBlank()

    /** 辅导员/班主任任一有名字才值得单独成卡。 */
    fun hasMentor(): Boolean =
        counselorName.isNotBlank() || classTeacherName.isNotBlank()
}

/**
 * hello.xjtu.edu.cn（迎新系统）个人信息接口。
 *
 * 鉴权不走 cookie：登录落地 URL 上的 JWT 要同时放进 `access-token` 与 `access_token`
 * 两个头（抓包里浏览器就是两个都发，服务端读哪个没验证过，照抄最稳），外加
 * `systemtype` 与 `synAccessSource`。
 */
class HelloApi(private val site: SiteSession) {

    private fun api(path: String): Request.Builder {
        val token = site.localToken["access_token"].orEmpty()
        if (token.isBlank()) {
            Log.w(TAG, "no access_token; site=${site.siteKey}@${System.identityHashCode(site)} keys=${site.localToken.keys} hasLogin=${site.hasLogin}")
            throw AuthExpiredException(site.siteName, "缺少访问令牌")
        }
        return Request.Builder()
            .url("${HelloLogin.BASE_URL}$path?synAccessSource=pc")
            .header("access-token", token)
            .header("access_token", token)
            .header("systemtype", site.localToken["system_type"] ?: "yingxin_student_pc")
            .header("synAccessSource", "pc")
            .header("Referer", "${HelloLogin.BASE_URL}/yingxin-pc/")
    }

    /** 拉取学生档案。失败抛异常，由调用方决定是否退回缓存。 */
    fun getProfile(): HelloProfile {
        val body = runBlocking { site.executeWithReAuth(api("/yingxin/user/afterLogin").get().build()) }
            .use { it.body?.string() ?: throw RuntimeException("个人信息接口返回空响应") }

        val json = body.safeParseJsonObject()
        val state = json.get("state")?.asInt
        if (state != 200) {
            val message = json.get("message")?.asString ?: "个人信息接口返回 state=$state"
            throw AuthExpiredException(site.siteName, message)
        }
        val data = json.getAsJsonObject("data") ?: throw RuntimeException("个人信息接口缺少 data")
        val stu = data.getAsJsonObject("studentBean") ?: throw RuntimeException("个人信息接口缺少 studentBean")
        val teacher = stu.getAsJsonObject("teacherBean")

        val profile = HelloProfile(
            name = stu.str("name"),
            sno = stu.str("sno").ifBlank { stu.str("account") },
            sex = stu.int("sex"),
            birthdate = stu.str("birthdate"),
            grade = stu.int("grade"),
            campusName = stu.str("campusName"),
            academyName = stu.str("academyName"),
            departmentName = stu.str("departmentName"),
            professionName = stu.str("professionName").stripLeadingCode(),
            className = stu.str("className"),
            schoolingLen = stu.int("schoolingLen"),
            enterSchoolDate = stu.str("enterSchoolDate"),
            cardId = stu.str("cardId"),
            pictureUrl = data.str("studentPictureUrl"),
            classTeacherName = teacher.str("classTeaName"),
            classTeacherPhone = teacher.str("classTeaPhone"),
            counselorName = teacher.str("counselorName"),
            counselorPhone = teacher.str("counselorPhone"),
            counselorOffice = teacher.str("counselorOffLoca"),
            fetchedAt = System.currentTimeMillis(),
        )
        Log.d(TAG, "getProfile ok: ${profile.name.take(1)}** ${profile.departmentName} ${profile.className}")
        return profile
    }

    private companion object {
        private const val TAG = "HelloApi"

        fun JsonObject?.str(key: String): String =
            this?.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

        fun JsonObject?.int(key: String): Int =
            this?.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asInt }?.getOrNull() ?: 0

        /**
         * 专业名带教务代码前缀，如 `0940数学与应用数学…`。展示时去掉纯数字前缀，
         * 匹配不到就原样返回——宁可多几个数字，也不要把正常专业名截断。
         */
        fun String.stripLeadingCode(): String =
            Regex("""^\d{4,}\s*""").replace(this, "").ifBlank { this }
    }
}
