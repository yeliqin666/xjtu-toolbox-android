package com.xjtu.toolbox.calendar

import android.content.Context
import com.xjtu.toolbox.notification.XjtuSiteFetcher
import org.jsoup.nodes.Document
import java.io.File
import java.security.MessageDigest

/**
 * 教务处发布的一张校历图片。
 * @param year 学年，如 "2026-2027"；页面上推不出来时为 null
 * @param url 原图地址（https）
 */
data class SchoolCalendarImage(val year: String?, val url: String) {
    val label: String get() = if (year != null) "$year 学年校历" else "校历"
    val fileName: String
        get() = "西安交通大学校历 ${year ?: "未标注学年"}.${url.substringAfterLast('.').substringBefore('?').lowercase()}"
    val mimeType: String get() = if (url.substringBefore('?').endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
}

/**
 * 教务处「校历」页（dean.xjtu.edu.cn/xxfw/xl.htm）上各学年的校历原图。
 *
 * 结构化的学期、假期数据仍来自 workflow 接口（[SchoolCalendarApi]）；这里补的是教务处
 * 正式发布的那张整学年校历图，方便对照、放大、存到手机。页面解析规则参照
 * XJTUToolBox 的 jwxt/calendar.py：每张校历是 `<a href="原图"><img …></a>`，按学年倒序排列。
 */
class SchoolCalendarImageApi(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "school_calendar").apply { mkdirs() }

    /** 页面上的全部校历图片，新学年在前。 */
    fun getImages(): List<SchoolCalendarImage> = parseImages(XjtuSiteFetcher.document(PAGE_URL))

    /** 原图字节。同一地址的图片缓存在 cacheDir，教务处换图会换文件名，不用担心过期。 */
    fun getImageBytes(image: SchoolCalendarImage): ByteArray {
        val file = File(cacheDir, sha1(image.url))
        if (file.isFile && file.length() > 0) return file.readBytes()
        val bytes = XjtuSiteFetcher.bytes(image.url, referer = PAGE_URL)
        runCatching {
            val tmp = File(cacheDir, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        return bytes
    }

    companion object {
        const val PAGE_URL = "https://dean.xjtu.edu.cn/xxfw/xl.htm"
        private val IMAGE_URL = Regex("""\.(?:jpe?g|png)(?:\?|$)""", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("""(\d{4})-(\d{4})""")

        /** 只认内嵌了图片的锚点；页面里混着 http://jwc.xjtu.edu.cn 的旧链接，统一升到 https。 */
        internal fun parseImages(doc: Document): List<SchoolCalendarImage> {
            val seen = HashSet<String>()
            val images = doc.select("a[href]").mapNotNull { a ->
                if (a.selectFirst("img") == null) return@mapNotNull null
                val url = a.absUrl("href").ifBlank { return@mapNotNull null }
                    .let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }
                if (!IMAGE_URL.containsMatchIn(url) || !seen.add(url)) return@mapNotNull null
                val fileName = url.substringBefore('?').substringAfterLast('/')
                val match = YEAR.find(a.attr("title")) ?: YEAR.find(fileName)
                SchoolCalendarImage(match?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }, url)
            }
            return inferYears(images)
        }

        /**
         * 页面按学年倒序：已知学年之后的每张依次减一年，遇到下一个已知学年重新对齐；
         * 开头若干张还未知的，从后面第一个已知学年往前推。
         */
        internal fun inferYears(images: List<SchoolCalendarImage>): List<SchoolCalendarImage> {
            fun shift(year: String, delta: Int): String {
                val (a, b) = year.split("-").map { it.toInt() + delta }
                return "$a-$b"
            }
            val years = images.map { it.year }.toMutableList()
            var expected: String? = null
            for (i in years.indices) {
                if (years[i] == null) years[i] = expected
                expected = years[i]?.let { shift(it, -1) }
            }
            val firstKnown = years.indexOfFirst { it != null }
            if (firstKnown > 0) {
                for (i in firstKnown - 1 downTo 0) years[i] = shift(years[i + 1]!!, 1)
            }
            return images.mapIndexed { i, image -> image.copy(year = years[i]) }
        }

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
