package com.xjtu.toolbox.schedule

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CourseColors] 的十六进制解析/回写，以及「设过色 / 没设过」的判定。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/schedule/CourseColorsHexTest.kt`：`CourseColors`
 * 搬进 commonMain 后一并搬来（留在 :app 只会重演交接文档 §1.1 那 10 个 `NoSuchMethodError`）。
 * 换壳：JUnit → kotlin.test；`assertNull(message, actual)` 的参数序在 kotlin.test 里是
 * `(actual, message)`。
 *
 * 后两个用例是**为了让搬迁后的改写有覆盖**才加的：
 *  - `toHex` 原来的 `"%06X".format(...)` 是 JVM 专有（且在 JVM 上是默认导入，import 判据看不见它），
 *    改成了手写补零 —— 只有 RGB 高位为 0 的颜色（如 `#0000FF`）能区分这两种写法；
 *  - `of()` 的判定从 `prefs.contains` 换成了 `KeyValueStore.contains`，而它存在的理由就是
 *    「用户正好把颜色设成某个默认值」时也不能被当成「没设过」。
 *
 * 这两个用例跑在 `:core:jvmTest`（jvm 的内存 KeyValueStore）里，Android 侧走同名 SharedPreferences。
 */
class CourseColorsHexTest {

    @Test
    fun `认三种写法，其余不认`() {
        assertEquals(Color(0xFF1565C0), CourseColors.parseHex("#1565C0"))
        assertEquals(Color(0xFF1565C0), CourseColors.parseHex(" 1565c0 "))
        assertEquals(Color(0xFFAABBCC), CourseColors.parseHex("#abc"))
        for (bad in listOf("", "#12345", "#1234567", "#GGGGGG", "red")) assertNull(CourseColors.parseHex(bad), bad)
    }

    @Test
    fun `带正负号的不认`() {
        for (bad in listOf("+abcde", "-12345", "#+12345", "#-abcde", "+12", "-1a")) {
            assertNull(CourseColors.parseHex(bad), bad)
        }
    }

    @Test
    fun `转回 hex 去掉透明度`() {
        assertEquals("#1565C0", CourseColors.toHex(Color(0xFF1565C0)))
        assertEquals("#1565C0", CourseColors.toHex(Color(0x801565C0)))
    }

    /** 补零：`"%06X"` 的对应物。手写 `padStart` 一旦漏掉，只会在这类颜色上露出来。 */
    @Test
    fun `低位色的十六进制补足六位`() {
        assertEquals("#0000FF", CourseColors.toHex(Color(0xFF0000FF)))
        assertEquals("#000000", CourseColors.toHex(Color(0xFF000000)))
        assertEquals("#010203", CourseColors.toHex(Color(0xFF010203)))
        // 往返：解析出来的颜色必须原样转回去
        for (hex in listOf("#0000FF", "#1565C0", "#abc", "#FFFFFF")) {
            assertEquals(hex.uppercase().let { if (it.length == 4) "#" + it.drop(1).flatMap { c -> listOf(c, c) }.joinToString("") else it },
                CourseColors.toHex(CourseColors.parseHex(hex)!!))
        }
    }

    @Test
    fun `设过色才返回颜色，恢复默认后回到 null`() {
        // 专属课名：jvm 的内存 store 是进程级的，别和别的用例互相污染
        val name = "颜色判定专用课-20261006"
        assertNull(CourseColors.of(name))
        CourseColors.set(name, Color(0xFF1565C0))
        assertEquals(Color(0xFF1565C0), CourseColors.of(name))
        // 改色覆盖
        CourseColors.set(name, Color(0xFF00FF00))
        assertEquals(Color(0xFF00FF00), CourseColors.of(name))
        // 恢复默认 = 删键 ⇒ 回到 null（而不是「读到某个默认值」）
        CourseColors.set(name, null)
        assertNull(CourseColors.of(name))
    }

    @Test
    fun `课名两端空白不影响同一门课`() {
        val name = "空白课名专用课-20261006"
        CourseColors.set("  $name  ", Color(0xFF112233))
        assertEquals(Color(0xFF112233), CourseColors.of(name))
        CourseColors.set(name, null)
    }
}
