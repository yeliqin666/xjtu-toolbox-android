package com.xjtu.toolbox.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterRedactTest {

    @Test
    fun stripsQueryStringStudentIdAndToken() {
        val msg = "java.io.IOException: GET https://cas.xjtu.edu.cn/login?service=x&ticket=ST-123 failed for 2211312345, " +
            "token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefghij"
        val out = CrashReporter.redact(msg)
        assertFalse(out.contains("ticket"))
        assertFalse(out.contains("2211312345"))
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(out.contains("https://cas.xjtu.edu.cn/login?…"))
    }

    @Test
    fun keepsStackFramesReadable() {
        val frame = "\tat com.xjtu.toolbox.a.b.c(SourceFile:1425)"
        assertEquals(frame, CrashReporter.redact(frame))
    }

    @Test
    fun reportsVersionAtCrashTime() {
        val log = "time=2026-09-29 16:53:09\nbuild=5.0.8 (85)\npreview=false\nthread=main\njava.lang.IllegalAccessError"
        assertEquals("5.0.8 (85)", CrashReporter.crashBuild(log))
        assertEquals(null, CrashReporter.crashBuild("thread=main\n"))
    }
}
