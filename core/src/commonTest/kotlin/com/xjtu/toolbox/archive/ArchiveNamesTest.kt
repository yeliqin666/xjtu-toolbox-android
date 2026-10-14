package com.xjtu.toolbox.archive

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 账号后缀纯函数的验收（§4 / A9）：
 * `suffixFor` 五档逐字、多对一碰撞必须被检测、端内名拆 base+suffix 最长后缀优先。
 */
class ArchiveNamesTest {

    @Test
    fun `A9 suffixFor 五档逐字：null 默认、空串下划线、逐字符替换`() {
        // null → "default"（无前导下划线）
        assertEquals("default", ArchiveNames.suffixFor(null))
        // "" → "_"（空串不是 null，进替换分支，结果单个下划线）
        assertEquals("_", ArchiveNames.suffixFor(""))
        // 纯数字
        assertEquals("_2253415556", ArchiveNames.suffixFor("2253415556"))
        // 非字母数字逐字符换 _
        assertEquals("_2_2534", ArchiveNames.suffixFor("2-2534"))
        // 逐字符（不是整串），长度不塌缩
        assertEquals("_a_b", ArchiveNames.suffixFor("a-b"))
    }

    @Test
    fun `A9 两个 accountId 映射到同一 suffix 必须被检测`() {
        // "a-b" 与 "a_b" 都落到 "_a_b"（多对一），必须报出碰撞
        assertEquals(
            listOf("_a_b"),
            ArchiveNames.detectCollision(
                listOf(
                    ArchiveAccount("a-b", "_a_b", "UNDERGRADUATE", 0L),
                    ArchiveAccount("a_b", "_a_b", "UNDERGRADUATE", 0L),
                )
            ),
        )

        // 无碰撞 = 空列表
        assertEquals(
            emptyList(),
            ArchiveNames.detectCollision(
                listOf(
                    ArchiveAccount("2253415556", "_2253415556", "UNDERGRADUATE", 0L),
                    ArchiveAccount("a-b", "_a_b", "UNDERGRADUATE", 0L),
                )
            ),
        )
    }

    @Test
    fun `端内名拆 base 与 suffix，最长后缀优先`() {
        val ids = ArchiveNames.split(
            listOf("schedule_cache_2253415556", "app_settings", "cookies_normal_2253415556"),
            listOf("_2253415556", "_2_2534"),
        )
        assertEquals(StoreId("schedule_cache_2253415556", "schedule_cache", "_2253415556"), ids[0])
        assertEquals(StoreId("app_settings", "app_settings", ""), ids[1])
        assertEquals(StoreId("cookies_normal_2253415556", "cookies_normal", "_2253415556"), ids[2])
    }
}
