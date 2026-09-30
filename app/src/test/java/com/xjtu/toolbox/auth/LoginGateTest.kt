package com.xjtu.toolbox.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class LoginGateTest {
    @Test
    fun `前台登录插到排队的后台登录前面`() = runBlocking {
        val gate = LoginGate()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        val holder = async { gate.withLock(foreground = false) { order += "holder"; release.await() } }
        yield()
        val background = async { gate.withLock(foreground = false) { order += "background" } }
        delay(20)
        val foreground = async { gate.withLock(foreground = true) { order += "foreground" } }
        delay(20)
        release.complete(Unit)
        awaitAll(holder, background, foreground)
        assertEquals(listOf("holder", "foreground", "background"), order)
    }

    @Test
    fun `同一时刻只有一个在跑`() = runBlocking {
        val gate = LoginGate()
        var running = 0
        var maxRunning = 0
        (1..6).map { i ->
            async {
                gate.withLock(foreground = i % 2 == 0) {
                    running++
                    maxRunning = maxOf(maxRunning, running)
                    delay(5)
                    running--
                }
            }
        }.awaitAll()
        assertEquals(1, maxRunning)
    }
}
