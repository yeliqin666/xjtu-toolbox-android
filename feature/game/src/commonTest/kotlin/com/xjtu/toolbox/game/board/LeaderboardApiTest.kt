package com.xjtu.toolbox.game.board

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaderboardApiTest {

    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun parsesBoardWithMeAndAnonymousFallback() {
        val snap = LeaderboardApi.parseBoard(
            """{"rows":[{"rank":1,"name":"甲","score":90},{"rank":2,"name":"","score":80}],"total":7,"me":{"rank":5,"name":"我","score":10}}"""
        )
        assertEquals(2, snap.rows.size)
        assertEquals(LeaderboardStore.ANONYMOUS, snap.rows[1].name)
        assertEquals(7, snap.total)
        assertEquals(BoardRow(5, "我", 10), snap.me)
        assertTrue(snap.qualifies(1), "榜没满时人人都能上")
    }

    @Test
    fun boardRequestHitsBoardEndpoint() = runTest {
        var seen = ""
        LeaderboardApi.install("https://example.test/games/", MockEngine { req ->
            seen = req.url.toString()
            respond("""{"rows":[],"total":0}""", HttpStatusCode.OK, json)
        })
        val snap = LeaderboardApi.board("2048", "u 1")
        assertTrue(seen.startsWith("https://example.test/games/board?game=2048&uid=u%201"), seen)
        assertNull(snap.me)
        LeaderboardApi.install("")
        assertFalse(LeaderboardApi.configured)
    }

    @Test
    fun serverErrorCarriesCode() = runTest {
        LeaderboardApi.install("https://example.test/games", MockEngine {
            respond("""{"error":"这个名字已被占用","code":"name_taken"}""", HttpStatusCode.Conflict, json)
        })
        val e = assertFailsWith<LeaderboardError> { LeaderboardApi.submit("hop", "u", "甲", 10) }
        assertEquals("这个名字已被占用", e.message)
        assertTrue(e.nameProblem)
        LeaderboardApi.install("")
    }

    @Test
    fun sanitizeName() {
        assertEquals("a b", Leaderboard.sanitizeName("  a\n\tb "))
        assertNull(Leaderboard.sanitizeName("   "))
        assertNull(Leaderboard.sanitizeName(LeaderboardStore.ANONYMOUS))
        assertEquals(Leaderboard.MAX_NAME, Leaderboard.sanitizeName("x".repeat(30))!!.length)
    }
}