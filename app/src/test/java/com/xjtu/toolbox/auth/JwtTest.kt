package com.xjtu.toolbox.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwtTest {
    private fun jwt(payload: String) =
        "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"

    @Test
    fun `按 exp 判过期，解不出来的不算`() {
        val token = jwt("""{"iat":1000,"exp":29800}""")
        assertEquals(29_800_000L, Jwt.expiresAtMs(token))
        assertFalse(Jwt.isExpired(token, 1000_000L))
        assertTrue(Jwt.isExpired(token, 29_800_000L))
        assertNull(Jwt.expiresAtMs("opaque-token"))
        assertNull(Jwt.expiresAtMs(jwt("""{"sub":"x"}""")))
        assertFalse(Jwt.isExpired("opaque-token", Long.MAX_VALUE))
    }
}
