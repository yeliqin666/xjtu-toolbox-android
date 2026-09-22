package com.xjtu.toolbox.dzpz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 P1 修的那个 bug：原来 TranscriptScreen.loadForm 的默认参数永远取
 * WORKFLOW_MAP 的第一个值（在校本科生 29），研究生、校友都被当成本科生处理。
 * 现在按「文件 × 身份」查表，这里锁死四档身份各自对应的 workflowId，
 * 防止以后改表时又把某一档漏掉或改错。
 */
class DzpzDocumentsTest {

    @Test
    fun transcript_hasCorrectWorkflowIdForEachIdentity() {
        val ids = DzpzDocuments.TRANSCRIPT.workflowIds
        assertEquals(29, ids[DzpzIdentity.UNDERGRAD])
        assertEquals(34, ids[DzpzIdentity.POSTGRAD])
        assertEquals(46, ids[DzpzIdentity.UNDERGRAD_ALUMNI])
        assertEquals(49, ids[DzpzIdentity.POSTGRAD_ALUMNI])
    }

    @Test
    fun transcript_coversAllFourIdentities() {
        val ids = DzpzDocuments.TRANSCRIPT.workflowIds
        assertEquals(DzpzIdentity.entries.size, ids.size)
        DzpzIdentity.entries.forEach { identity ->
            assertNotNull("缺了 $identity 对应的 workflowId", ids[identity])
        }
    }

    @Test
    fun all_containsTranscript() {
        assertTrue(DzpzDocuments.all.contains(DzpzDocuments.TRANSCRIPT))
    }
}
