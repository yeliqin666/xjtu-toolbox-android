package com.xjtu.toolbox.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 场馆会话失效时，站点把请求带去它自己的登录页（用户名密码表单，不是统一认证）。
 * 认不出来的话订单页会把登录页当数据解析，用户看到的是一堆网页文字（#场馆订单）。
 */
class VenueSessionAuthTest {

    private fun response(url: String, body: String, code: Int = 200, contentType: String = "text/html;charset=utf-8") =
        Response.Builder()
            .request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .header("Content-Type", contentType)
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    private val session = VenueSession()

    @Test
    fun 停在场馆站自己的登录页算登录失效() {
        val r = response("http://202.117.17.144:8080/web/login/pre.html?continueurl=http://202.117.17.144:8080/web/yyuser/searchorder.html", "<title>登录</title>")
        assertTrue(session.isAuthFailureResponse(r, "<title>登录</title>"))
    }

    @Test
    fun 正常的订单数据和业务页不算() {
        val json = response("http://202.117.17.144:8080/web/yyuser/searchorder.html?page=1", """[{"orderid":"1"}]""", contentType = "text/plain;charset=UTF-8")
        assertFalse(session.isAuthFailureResponse(json, """[{"orderid":"1"}]"""))
        val page = response("http://202.117.17.144:8080/web/order/orderlist.html", "<title>订单</title>")
        assertFalse(session.isAuthFailureResponse(page, "<title>订单</title>"))
    }
}