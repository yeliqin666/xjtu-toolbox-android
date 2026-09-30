package com.xjtu.toolbox.network

import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class HttpClientsTest {
    private lateinit var server: HttpServer
    private val seen = ConcurrentHashMap<String, String>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen[ex.requestURI.path] = ex.requestHeaders.getFirst("User-Agent").orEmpty()
            if (ex.requestURI.path == "/start") {
                ex.responseHeaders.add("Location", "/cas/login")
                ex.sendResponseHeaders(302, -1)
            } else {
                ex.sendResponseHeaders(200, -1)
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun defaultUaFollowsEveryRedirectHop() {
        val client = HttpClients.base.newBuilder().build()
        client.newCall(Request.Builder().url(url("/start")).build()).execute().close()
        assertEquals(APP_UA, seen["/start"])
        assertEquals(APP_UA, seen["/cas/login"])
    }

    @Test
    fun explicitUaIsKept() {
        HttpClients.base.newCall(
            Request.Builder().url(url("/start")).header("User-Agent", "x SuperApp").build(),
        ).execute().close()
        assertEquals("x SuperApp", seen["/start"])
        assertEquals("x SuperApp", seen["/cas/login"])
    }
}
