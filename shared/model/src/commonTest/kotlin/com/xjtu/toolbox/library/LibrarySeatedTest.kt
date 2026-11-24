package com.xjtu.toolbox.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「人是否在馆」这条判据。它决定自动勿扰开不开，两种情况最容易搞错：
 * 远程在宿舍约的座（状态也是「已预约」）不能被当成入馆；暂离、已离馆不能还挂着勿扰。
 */
class LibrarySeatedTest {

    @Test
    fun insideOnlyWhenSchoolSaysSo() {
        assertTrue(isSeatedInside("使用中", seatedAtDesk = false), "闸机签过到，学校认的在馆")
        assertFalse(isSeatedInside("已预约", seatedAtDesk = false), "远程预约：人不在馆，不该开勿扰")
        assertFalse(isSeatedInside("暂离", seatedAtDesk = true), "暂离人可能已经走了，不算在馆")
        assertFalse(isSeatedInside(null, seatedAtDesk = true), "没有预约就没有人")
    }

    @Test
    fun scanningAtTheDeskCountsAsSeated() {
        // 扫码约座时人已经坐在座位前，学校那一刻还写着「已预约」——按用户要求，这算入座
        assertTrue(isSeatedInside("已预约", seatedAtDesk = true))
        assertTrue(isSeatedInside("使用中", seatedAtDesk = true))
    }

    @Test
    fun finishedBookingsNeverCount() {
        listOf("已取消", "已完成", "已过期", "已失效", "已违约", "超时未入馆", "已离馆").forEach { status ->
            assertFalse(isSeatedInside(status, seatedAtDesk = true), "$status 不该算在馆")
        }
    }
}
