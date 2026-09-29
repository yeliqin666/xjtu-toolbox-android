package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SsnLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DormPowerTest {

    @Test
    fun `响应按 UTF-8 解，不合法再按 GBK`() {
        assertEquals("{\"msg\":\"电费\"}", DormPowerParsers.decode("{\"msg\":\"电费\"}".toByteArray(Charsets.UTF_8)))
        assertEquals("{\"msg\":\"电费\"}", DormPowerParsers.decode("{\"msg\":\"电费\"}".toByteArray(charset("GBK"))))
    }

    @Test
    fun `code 为 0 取 data，401001 算登录过期，其他抛出 msg`() {
        assertEquals("[]", DormPowerParsers.data("""{"code":0,"data":[],"msg":"ok"}""").toString())
        try {
            DormPowerParsers.data("""{"code":401001,"msg":"expired"}""")
            fail()
        } catch (_: AuthExpiredException) {
        }
        val e = runCatching { DormPowerParsers.data("""{"code":500,"msg":"房间不存在"}""") }.exceptionOrNull()
        assertEquals("房间不存在", e?.message)
        assertTrue(runCatching { DormPowerParsers.data("<html>") }.isFailure)
    }

    @Test
    fun `已绑定房间跳过没有 roomId 的项，电量查不到为 null`() {
        val rooms = DormPowerParsers.rooms(
            DormPowerParsers.data("""{"code":0,"data":[{"roomId":"r1","roomName":"梧桐/1栋/101"},{"roomName":"坏项"}]}"""),
        )
        assertEquals(listOf(DormRoom("r1", "梧桐/1栋/101")), rooms)
        assertEquals(12.5, DormPowerParsers.kwh(DormPowerParsers.data("""{"code":0,"data":12.5}""")))
        assertEquals(3.0, DormPowerParsers.kwh(DormPowerParsers.data("""{"code":0,"data":"3"}""")))
        assertNull(DormPowerParsers.kwh(DormPowerParsers.data("""{"code":0,"data":null}""")))
    }

    @Test
    fun `房间树逐级解析，无子级的是叶子`() {
        val tree = DormPowerParsers.tree(
            DormPowerParsers.data(
                """{"code":0,"data":[{"value":"a","name":"园区","children":[{"value":"b","name":"1栋","children":[]}]}]}""",
            ),
        )
        assertEquals("园区", tree.single().name)
        assertTrue(!tree.single().isLeaf)
        assertTrue(tree.single().children.single().isLeaf)
    }

    @Test
    fun `页面里的 cid：未登录（loginUrl 非空）或没有时为 null`() {
        val cid = "0123456789abcdef0123456789abcdef"
        assertEquals(cid, SsnLogin.parseCid("""var loginUrl = ""; var cid = "$cid";"""))
        assertNull(SsnLogin.parseCid("""var loginUrl = "https://org.xjtu.edu.cn/x"; var cid = "$cid";"""))
        assertNull(SsnLogin.parseCid("<html></html>"))
    }

    @Test
    fun `低电只报低于阈值里最少的那间，没读到的不算`() {
        fun r(id: String, kwh: Double?) = DormReading(DormRoom(id, id), kwh, 0L)
        assertEquals("b", DormPowerStore.lowest(listOf(r("a", 30.0), r("b", 2.0), r("c", 5.0), r("d", null)))?.room?.id)
        assertNull(DormPowerStore.lowest(listOf(r("a", LOW_KWH), r("d", null))))
    }

    @Test
    fun `房间简称取全名最后三级`() {
        assertEquals("2层 · 01室 · A", DormRoom("1", "园区/1栋/2层/01室/A").shortName())
        assertEquals("宿舍", DormRoom("1", "宿舍").shortName())
    }
}
