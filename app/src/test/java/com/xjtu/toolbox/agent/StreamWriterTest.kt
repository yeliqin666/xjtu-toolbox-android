package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式写入协调器的不变量测试。
 *
 * 这里测的是**编排**而不是字符串拼接：什么时候重置、什么时候必须强制写回。用假时钟
 * 精确驱动，把两个用户可见的正确性要求钉死：
 *
 * 1. 不丢字——本段收到的每个增量最终都要写出去；
 * 2. 不串段——换段时上一段已写出的文字不被覆盖。
 *
 * 第 2 条是这次改动里最容易悄悄坏掉的地方：收尾时正文会被模型返回的完整文本覆盖，
 * 但思考内容只经这里写入，换段忘了强制写回就会少一截。
 */
class StreamWriterTest {

    /** 记录每次写入的 (content, reasoning) 快照，模拟真实落到消息上的效果。 */
    private class Recorder {
        val writes = mutableListOf<Pair<String, String>>()
        var fakeNow = 1_000_000_000L

        val writer = StreamWriter(
            write = { c, r -> writes.add(c to r) },
            now = { fakeNow },
        )

        fun advance(ns: Long) { fakeNow += ns }

        /** 最终落在消息上的状态 = 最后一次写入（含空串写入）。 */
        fun latest(): Pair<String, String> = writes.lastOrNull() ?: ("" to "")
    }

    @Test
    fun `首个增量立即写出，不留空气泡`() {
        val rec = Recorder()
        rec.writer.appendContent("你")
        assertEquals(1, rec.writes.size)
        assertEquals("你" to "", rec.latest())
    }

    @Test
    fun `间隔内的增量被合并，不逐个写`() {
        val rec = Recorder()
        rec.writer.appendContent("你")
        rec.advance(1_000_000L)   // 1ms，不到一帧
        rec.writer.appendContent("好")
        rec.advance(1_000_000L)
        rec.writer.appendContent("呀")
        // 只写了第一次，后两个还压在缓冲里
        assertEquals(1, rec.writes.size)
    }

    @Test
    fun `合并不丢字：强制写回后拿到完整文本`() {
        val rec = Recorder()
        val frags = listOf("你", "好", "呀", "，", "世", "界")
        frags.forEach { f ->
            rec.writer.appendContent(f)
            rec.advance(1_000_000L)
        }
        rec.writer.flush(force = true)
        assertEquals(frags.joinToString(""), rec.latest().first)
    }

    @Test
    fun `正文与思考各自累积、互不干扰`() {
        val rec = Recorder()
        rec.writer.appendReasoning("先想")
        rec.writer.appendContent("再看")
        rec.advance(100_000_000L)
        rec.writer.appendReasoning("一下")
        rec.writer.flush(force = true)
        val (c, r) = rec.latest()
        assertEquals("再看", c)
        assertEquals("先想一下", r)
    }

    @Test
    fun `强制写回无视间隔`() {
        val rec = Recorder()
        rec.writer.appendContent("甲")
        rec.advance(1L)   // 刚写过
        val wrote = rec.writer.flush(force = true)
        assertTrue("强制写回应当真的写入", wrote)
    }

    @Test
    fun `没有内容时强制写回不产生空写入`() {
        val rec = Recorder()
        assertFalse(rec.writer.flush(force = true))
        assertEquals(0, rec.writes.size)
    }

    @Test
    fun `换段会清空缓冲，新段从空开始且首个增量立即上屏`() {
        val rec = Recorder()
        rec.writer.appendContent("第一段")
        rec.writer.endSegment()

        // 新段第一个增量不该把上一段的内容再带出来
        rec.writer.appendContent("第二段")
        assertEquals("第二段", rec.latest().first)
        assertFalse(
            "新段写入不应再包含上一段的文字",
            rec.latest().first.contains("第一段"),
        )
    }

    @Test
    fun `换段不会丢掉上一段已收到的字`() {
        val rec = Recorder()
        rec.writer.appendContent("工具调用前")
        rec.advance(1_000_000L)   // 不到一帧，还没自动写
        rec.writer.appendContent("的半句话")
        rec.writer.endSegment()   // 换段：必须强制写回
        assertEquals("工具调用前的半句话", rec.latest().first)
    }

    @Test
    fun `换段后时间片重新计时`() {
        val rec = Recorder()
        rec.writer.appendContent("甲")
        rec.writer.endSegment()
        // 紧接着（0ms）写新段：因为计时已归零，应立刻写出而不是被上一段的间隔挡住
        rec.writer.appendContent("乙")
        assertEquals("乙", rec.latest().first)
    }

    @Test
    fun `token 洪峰下写入次数被压到约每帧一次`() {
        val rec = Recorder()
        // 每 5ms 一个增量，持续 1 秒 = 200 个增量
        repeat(200) {
            rec.writer.appendContent("x")
            rec.advance(5_000_000L)
        }
        rec.writer.flush(force = true)
        assertTrue("写入 ${rec.writes.size} 次应显著少于 200", rec.writes.size < 80)
        assertTrue("写入 ${rec.writes.size} 次不该过稀", rec.writes.size >= 50)
        // 而且最终文本一个不差
        assertEquals("x".repeat(200), rec.latest().first)
    }
}
