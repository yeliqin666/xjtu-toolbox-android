package com.xjtu.toolbox.social

import com.xjtu.toolbox.platform.UrlSafeBase64
import com.xjtu.toolbox.platform.ZlibCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 分享码在两个实现之间互认这件事的机器可验版本。 */
class ShareCodeInteropJvmTest {

    /** 夹具档案（编造）：20 个字段每一格都有值，且每一格的形状都过得了 `MatchProfile.decode` 的校验。 */
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
    fun `Android 那一份编出来的码 浏览器那两份编解码器解得回同一段明文`() {
        // 这一枪走的是 `MatchProfile.encode` → 缝 → jvmMain 的 actual（`java.util.zip` + `java.util.Base64`），
        // 也就是 Android 那一份**同一份源码**。
        val code = MatchProfile.encode(fixture)
        assertTrue(code.isNotEmpty(), "jvm/Android 上有 deflate 与 Base64，不该是空串")

        // 浏览器那一侧的两步（纯 Kotlin）：Base64Url 解 → zlib 解。
        val packed = assertNotNull(UrlSafeBase64.decode(code), "纯 Kotlin 的 Base64 解不开 Android 编的码")
        val raw = assertNotNull(ZlibCodec.inflate(packed, 256 * 1024), "纯 Kotlin 的 zlib 解不开 Android 编的流")
            .decodeToString()

        val parts = raw.split("|")
        assertEquals(20, parts.size, "字段数变了：$raw")
        assertEquals("5", parts[0], "版本号变了")
        assertEquals(fixture.nickname, parts[1])
        assertEquals(fixture.busyGrid, parts[2])
        assertEquals("CS0001~高等数学（一）,CS0002~大学物理", parts[3])
        assertEquals(fixture.buildingGrid, parts[4])
        assertEquals("中2,主楼", parts[5])
        assertEquals("张三,李四", parts[6])
        assertEquals(fixture.className, parts[18])
        assertEquals(fixture.province, parts[19])
    }

    @Test
    fun `浏览器那一份编出来的码 MatchProfile 解得回同一个档案`() {
        // 浏览器那一侧的两步（纯 Kotlin）：zlib 压 → Base64Url 编。明文的字段顺序与 `MatchProfile.encode` 同源。
        val raw = listOf(
            "5",
            fixture.nickname,
            fixture.busyGrid,
            fixture.courses.joinToString(",") { "${it.code}~${it.name}" },
            fixture.buildingGrid,
            fixture.buildings.joinToString(","),
            fixture.teachers.sorted().joinToString(","),
            fixture.pastCourseCodes.sorted().joinToString(","),
            fixture.exams.joinToString(",") { "${it.code}~${it.name}~${it.date}" },
            fixture.textbooks.sorted().joinToString(","),
            fixture.diningHours,
            fixture.canteens.sorted().joinToString(","),
            fixture.dietTags.sorted().joinToString(","),
            fixture.grade.toString(),
            fixture.profession,
            fixture.department,
            fixture.academy,
            fixture.campus,
            fixture.className,
            fixture.province,
        ).joinToString("|")
        val code = UrlSafeBase64.encode(
            assertNotNull(ZlibCodec.deflate(raw.encodeToByteArray()), "纯 Kotlin 压不出来"),
        )
        assertTrue(code.none { it == '=' || it == '+' || it == '/' }, "分享码是 URL-safe、无填充的 Base64：$code")

        // 这一枪走的是 `MatchProfile.decode` → 缝 → jvmMain 的 actual（也就是 Android 那一份）。
        assertEquals(fixture, MatchProfile.decode(code), "Android 那一份解不出来自浏览器那一份编法的码")
    }

    @Test
    fun `冻结的那条真码（Android 编的）这一端解得回夹具档案`() {
        assertEquals(fixture, MatchProfile.decode(ANDROID_MADE_SHARE_CODE))
        // 同一份常数在 `:web:wasmJsBrowserTest` 的 `WebMatchShareCodeTest` 里也解一遍（跨端各钉一次）。
        assertTrue(ANDROID_MADE_SHARE_CODE.length in 100..1800, "码长应当在「扫得动」的范围内")
    }

    private companion object {
        /** 用 Android/jvm 实现（`Deflater(BEST_COMPRESSION)` + URL Base64）对上面那份夹具编出的真码。 */
        const val ANDROID_MADE_SHARE_CODE =
            "eNqtkEkKwkAQRS8UpbtFL-ARvE4RRFEMiigoJkSx4xAViagoxCSQy_SQXnkFGwWHjav8RVFU_V88qgzKTqUfA8YY5Sao1nTFptrbMrDE" +
                "-MgD_550WFi_J5bxXBKTrzZ6LK2dHLQB4WJ-AhYGxGBhLNYJ8ISy0DLErM9d9wX2n88kiFQKCBcwgm9G3RjfAXajamErV8O_9ftE0K6sO1TLO" +
                "acNkBcK-nQJsoMnDxMxjeQpBjm6stTTRtXcAovPWffEbr5yFsBbFx61BfV4L_pkSAlhUM44W6cPFSbDWg"
    }
}
