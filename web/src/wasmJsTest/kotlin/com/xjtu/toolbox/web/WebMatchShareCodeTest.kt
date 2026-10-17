package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.base64UrlDecode
import com.xjtu.toolbox.platform.base64UrlEncode
import com.xjtu.toolbox.platform.code128Modules
import com.xjtu.toolbox.platform.deflateBytes
import com.xjtu.toolbox.platform.inflateBytes
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.social.MatchProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 浏览器这一端的分享码真能用在真浏览器里的证明。 */
class WebMatchShareCodeTest {

    /** 与 `ShareCodeInteropJvmTest` 里那份夹具相同（编造）。 */
    private val fixture = MatchProfile.Profile(
        nickname = "阿离",
        busyGrid = "111" + "0".repeat(74),
        courses = listOf(
            MatchProfile.SharedCourse("CS0001", "高等数学（一）"),
            MatchProfile.SharedCourse("CS0002", "大学物理"),
        ),
        buildingGrid = "01" + ".".repeat(75),
        buildings = listOf("中2", "主楼"),
        teachers = setOf("张三", "李四"),
        pastCourseCodes = setOf("CS0000"),
        exams = listOf(MatchProfile.SharedExam("CS0001", "高等数学（一）", "2026-01-10")),
        textbooks = setOf("大学物理学", "高等数学习题集"),
        diningHours = "000000000011000000000000",
        canteens = setOf("学苑食堂"),
        dietTags = setOf("素"),
        grade = 2023,
        profession = "计算机类",
        department = "电信学部",
        academy = "仲英书院",
        campus = "兴庆校区",
        className = "计算机2301",
        province = "陕西",
    )

    @Test
    fun `这一端的两条缝都在：codecAvailable 是 true`() {
        assertTrue(
            MatchProfile.codecAvailable,
            "wasmJs 上 deflate 与 Base64 两条缝都该在（D12 ②）；不在的话分享码生成与解析都不可用",
        )
        // 两条缝本身也各走一发：压了能解、编了能解。
        val raw = "阿离 的课表".encodeToByteArray()
        val packed = assertNotNull(deflateBytes(raw), "这一端压不出来")
        assertEquals("阿离 的课表", assertNotNull(inflateBytes(packed, 1 shl 16)).decodeToString())
        assertEquals(raw.decodeToString(), assertNotNull(base64UrlDecode(base64UrlEncode(raw))).decodeToString())
    }

    @Test
    fun `Android 那一端编出来的真码 这一端解得回同一份档案`() {
        assertEquals(fixture, MatchProfile.decode(ANDROID_MADE_SHARE_CODE))
    }

    @Test
    fun `这一端自己编出来的码这一端解得回来，而且二维码画得出来`() {
        val code = MatchProfile.encode(fixture)
        assertTrue(code.isNotEmpty(), "这一端的 encode 不该是空串")
        assertTrue(code.none { it == '=' || it == '+' || it == '/' }, "分享码是 URL-safe、无填充的 Base64")
        assertEquals(fixture, MatchProfile.decode(code), "自己编的码自己解不回来")

        // 「扫一下」那条路上浏览器这一侧的第二半：把这段码画成二维码（wasm 上是纯 Kotlin 的 QrCodec）。
        val matrix = assertNotNull(qrCodeModules(code), "这一端画不出分享码的二维码")
        assertTrue(matrix.width == matrix.height && matrix.width >= 21, "二维码该是方的、至少 21 格")
        // 付款码那条路上的条形码也一并确认在这一端编得出来（20 位十进制那一档）。
        val bars = assertNotNull(code128Modules("12345678901234567890"), "这一端画不出付款码的条形码")
        assertEquals(1, bars.height, "条纹阵列的高度是 1（渲染方拉伸）")
        assertTrue(bars.width > 100, "20 位数字的条纹该有一百多个模块宽：${bars.width}")
    }

    private companion object {
        /** 用 Android/jvm 实现对上面那份夹具编出的真码。 */
        const val ANDROID_MADE_SHARE_CODE =
            "eNqtkEkKwkAQRS8UpbtFL-ARvE4RRFEMiigoJkSx4xAViagoxCSQy_SQXnkFGwWHjav8RVFU_V88qgzKTqUfA8YY5Sao1nTFptrbMrDE" +
                "-MgD_550WFi_J5bxXBKTrzZ6LK2dHLQB4WJ-AhYGxGBhLNYJ8ISy0DLErM9d9wX2n88kiFQKCBcwgm9G3RjfAXajamErV8O_9ftE0K6sO1TLO" +
                "acNkBcK-nQJsoMnDxMxjeQpBjm6stTTRtXcAovPWffEbr5yFsBbFx61BfV4L_pkSAlhUM44W6cPFSbDWg"
    }
}
