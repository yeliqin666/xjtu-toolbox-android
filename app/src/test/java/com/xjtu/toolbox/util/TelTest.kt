package com.xjtu.toolbox.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [toDialableTel] 的边界：校方数据是人手填的，格式不统一，这里把见过的形态都钉住。
 *
 * 重点不只是「能解析出来」，还有**解析不出来时必须给空串**——调用方靠这个空串
 * 决定号码不画成蓝色，返回一串垃圾等于给出一个点不动的假链接。
 */
class TelTest {

    @Test
    fun `裸号码原样返回`() {
        assertEquals("13812345678", "13812345678".toDialableTel())
        assertEquals("82668888", "82668888".toDialableTel())
    }

    @Test
    fun `黄页那种带区号的固话行为不变`() {
        // 黄页 dialNumber 迁移过来之前就是取第一段 7 位以上数字，即丢掉 029。
        // 校内拨固话本来就只拨后 8 位，这个行为不能因为重构而改变。
        assertEquals("82668888", "029-82668888".toDialableTel())
    }

    @Test
    fun `空白被抹掉，分段写的手机号能拼起来`() {
        assertEquals("13812345678", "138 1234 5678".toDialableTel())
        // 全角空格
        assertEquals("13812345678", "138\u30001234\u30005678".toDialableTel())
        assertEquals("82668888", "8266 8888".toDialableTel())
    }

    @Test
    fun `连字符不能被抹掉，否则分机号会被粘进主号码`() {
        assertEquals("82668888", "82668888-101".toDialableTel())
        assertEquals("82668888", "82668888 转 801".toDialableTel())
    }

    @Test
    fun `国家码去掉，只留能拨的那 11 位`() {
        assertEquals("13812345678", "+86 138 1234 5678".toDialableTel())
        assertEquals("13812345678", "8613812345678".toDialableTel())
        assertEquals("13812345678", "+8613812345678".toDialableTel())
    }

    @Test
    fun `本来以 86 开头但不是国家码的号码不该被误伤`() {
        // 后面不是 11 位手机号，所以 86 是号码本身的一部分。
        assertEquals("8612345", "8612345".toDialableTel())
        assertEquals("86101234567", "86101234567".toDialableTel())
    }

    @Test
    fun `多个号码取第一个`() {
        assertEquals("88968888", "创新港：8896 8888，兴庆：8266 8888".toDialableTel())
    }

    @Test
    fun `解析不出号码时返回空串`() {
        assertEquals("", "".toDialableTel())
        assertEquals("", "   ".toDialableTel())
        assertEquals("", "见年级群".toDialableTel())
        // 位数不够，多半是房间号或分机，不该当成电话
        assertEquals("", "123456".toDialableTel())
    }
}
