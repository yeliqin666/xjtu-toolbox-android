package com.xjtu.toolbox.fitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessProtocolTest {

    private fun callback(query: String = "", overrides: Map<String, String> = emptyMap()): String {
        val values = mutableMapOf(
            "timestamp" to "1700000000",
            "nonce" to "123456",
            "course_id" to "course-id",
            "uid" to "user-id",
            "card_id" to "card-id",
            "login_type" to "4",
            "type" to "1",
            "school_id" to "school-id",
            "student_num" to "student-id",
            "user_type" to "2",
            "token" to "session-token",
            "sign" to "launch-signature",
            "term_id" to "term-id",
        )
        values.putAll(overrides)
        val fragment = values.entries.joinToString("&") { "${it.key}=${it.value}" }
        val querySuffix = if (query.isBlank()) "" else "?$query"
        return "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/view/h5xajt/$querySuffix#/pages/index/index?$fragment"
    }

    @Test
    fun extract_readsFragmentNotQuery() {
        val launch = FitnessProtocol.extractLaunch(
            callback(query = "token=query-token&sign=query-sign")
        )
        assertEquals("session-token", launch.session["token"])
        assertNotEquals("query-token", launch.session["token"])
        assertEquals(1, launch.session["role"])
        assertEquals("5", launch.session["ostype"])
        assertEquals(
            "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/view/h5xajt/#/pages/index/index",
            launch.referer,
        )
        assertNull(launch.session["course_id"])
        assertNull(launch.session["sign"])
        assertNull(launch.session["user_type"])
    }

    @Test(expected = RuntimeException::class)
    fun extract_rejectsMissingUid() {
        FitnessProtocol.extractLaunch(callback(overrides = mapOf("uid" to "")))
    }

    @Test(expected = RuntimeException::class)
    fun extract_rejectsUnknownUserType() {
        FitnessProtocol.extractLaunch(callback(overrides = mapOf("user_type" to "99")))
    }

    @Test
    fun payload_signMatchesSortedFields() {
        val session = FitnessProtocol.extractLaunch(callback()).session
        val payload = FitnessProtocol.buildApiPayload(
            session,
            extra = mapOf("uid" to "user-id"),
            timestamp = 1_800_000_000L,
            nonce = "654321",
        )
        val copy = payload.toMutableMap()
        val sign = copy.remove("sign") as String
        val source = copy.keys.sorted().joinToString("") { "$it${copy[it]}" } + FitnessProtocol.SIGN_SALT
        val expected = java.security.MessageDigest.getInstance("MD5")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, sign)
        assertEquals(1, payload["role"])
        assertEquals(0, payload["class_id"])
        assertEquals(1, payload["version"])
        assertEquals("5", payload["ostype"])
        assertEquals(1_800_000_000L, payload["timestamp"])
    }

    @Test
    fun encrypt_roundTripAndEnvelope() {
        val original = linkedMapOf<String, Any>("uid" to "user-id", "token" to "session-token")
        val cipher = FitnessProtocol.encryptPayload(original)
        val decrypted = FitnessProtocol.decryptPayload(cipher)
        assertNotNull(decrypted)
        val envelope = """{"status":1,"info":"ok","is_encrypt":1,"data":"$cipher"}"""
        val user = FitnessProtocol.unwrapUserInfo(envelope)
        assertEquals("user-id", user?.get("uid")?.asString)
        assertEquals("user-id", FitnessProtocol.unwrapUserInfo("\n$cipher\r\n")?.get("uid")?.asString)
        assertNull(FitnessProtocol.unwrapUserInfo("""{"status":1,"data":"not-base64"}"""))
        assertNull(FitnessProtocol.unwrapUserInfo("""{"status":1,"data":{}}"""))
    }
}
