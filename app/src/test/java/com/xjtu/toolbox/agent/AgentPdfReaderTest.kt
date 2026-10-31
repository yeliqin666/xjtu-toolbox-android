package com.xjtu.toolbox.agent

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AgentPdfReaderTest {
    @Test
    fun `长页可无丢字续读 空文字页返回图像候选`() {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            pdf.addPage(PDPage())
            val original = "0123456789".repeat(1500)
            PDPageContentStream(pdf, page).use {
                it.beginText()
                it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(50f, 700f)
                it.showText(original)
                it.endText()
            }
            val first = AgentPdfReader.text(pdf, "1-2", 0).first
            val offset = Regex("text_offset=(\\d+)").find(first)!!.groupValues[1].toInt()
            assertTrue(first.contains("unread_pages: 2"))
            val rest = AgentPdfReader.text(pdf, "1", offset).first
            assertEquals(original, first.substringAfter("】\n").substringBefore("\ntext_truncated:") + rest.substringAfter("】\n").trimEnd())
            assertEquals(listOf(2), AgentPdfReader.text(pdf, "2", 0).second)
        }
    }

    @Test
    fun `真实PDF按离散页提取文字并支持单页偏移`() {
        PDDocument().use { source ->
            listOf("First page", "Second page", "Third page").forEach { text ->
                val page = PDPage()
                source.addPage(page)
                PDPageContentStream(source, page).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 12f)
                    it.newLineAtOffset(50f, 700f)
                    it.showText(text)
                    it.endText()
                }
            }
            val bytes = java.io.ByteArrayOutputStream().also { source.save(it) }.toByteArray()
            PDDocument.load(bytes).use { pdf ->
                val (text, empty) = AgentPdfReader.text(pdf, "3,1", 0)
                assertTrue(text.indexOf("Third page") < text.indexOf("First page"))
                assertFalse(text.contains("Second page"))
                assertTrue(empty.isEmpty())
                assertTrue(AgentPdfReader.text(pdf, "2", 7).first.contains("page"))
                assertFalse(AgentPdfReader.text(pdf, "2", 7).first.contains("Second"))
            }
        }
    }

    @Test
    fun `元数据和嵌套目录指向真实物理页`() {
        PDDocument().use { pdf ->
            repeat(3) { pdf.addPage(PDPage()) }
            pdf.documentInformation.title = "Calculus"
            pdf.documentInformation.author = "Teacher"
            val root = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = root
            val chapter = PDOutlineItem().apply {
                title = "Chapter 1"
                destination = PDPageFitDestination().apply { page = pdf.getPage(1) }
            }
            root.addLast(chapter)
            chapter.addLast(PDOutlineItem().apply {
                title = "Exercises"
                destination = PDPageFitDestination().apply { page = pdf.getPage(2) }
            })
            val metadata = AgentPdfReader.metadata(pdf, "lecture.pdf", 123)
            assertTrue(metadata.contains("title: Calculus"))
            assertTrue(metadata.contains("author: Teacher"))
            assertTrue(metadata.contains("pages: 3"))
            val outline = AgentPdfReader.outline(pdf)
            assertTrue(outline.contains("- Chapter 1; page: 2"))
            assertTrue(outline.contains("  - Exercises; page: 3"))
        }
    }

    @Test
    fun `无书签不伪造目录`() {
        PDDocument().use { assertEquals("outline: none", AgentPdfReader.outline(it)) }
    }
}
