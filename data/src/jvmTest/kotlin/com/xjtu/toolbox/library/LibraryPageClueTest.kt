package com.xjtu.toolbox.library

import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * `LibraryApi.pageClue` —— 「收到的不是图书馆数据时，报错里带上是哪个页面」那一条。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/library/LibraryPageClueTest.kt`，随 [LibraryApi]
 * 一起搬进 `:data:jvmTest`（`pageClue` 是 `internal`，只有同模块看得见；它本来就是本类的私有判据，
 * 不是为了跨模块才存在）。断言逐条保留，只把 JUnit4 的注解/断言换成 `kotlin.test`
 *（`:data` 的测试壳统一是这个）。
 */
class LibraryPageClueTest {

    private fun response(url: String, code: Int = 200) = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code).message("")
        .build()

    @Test
    fun direct_usesTitle() {
        val clue = LibraryApi.pageClue(
            response("http://rg.lib.xjtu.edu.cn:8086/qspace?floor=xingqing2floor"),
            "<html><head><title>校园网认证</title></head><body>请登录</body></html>",
        )
        assertEquals("直连 · 状态码 200 · 校园网认证", clue)
    }

    @Test
    fun webVpn_noTitle_fallsBackToBodyText() {
        val clue = LibraryApi.pageClue(
            response("https://webvpn.xjtu.edu.cn/http-8086/abc/qspace"),
            "<html><body><p>您没有访问该资源的权限，请联系管理员处理后再试</p></body></html>",
        )
        assertEquals("WebVPN · 状态码 200 · 您没有访问该资源的权限，请联系管理员处理", clue)
    }

    @Test
    fun emptyBody() {
        assertEquals("直连 · 状态码 200 · 空页面", LibraryApi.pageClue(response("http://rg.lib.xjtu.edu.cn:8086/qspace"), ""))
    }
}
