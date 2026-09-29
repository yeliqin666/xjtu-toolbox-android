package com.xjtu.toolbox.dzpz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DzpzDocumentsTest {

    /** 只开放在校本科生的成绩单流程（29）；改这个 id 等于换了申请流程，得有意为之。 */
    @Test
    fun transcript_usesUndergradWorkflow() {
        assertEquals(29, DzpzDocuments.TRANSCRIPT.workflowId)
    }

    @Test
    fun all_containsTranscript() {
        assertTrue(DzpzDocuments.all.contains(DzpzDocuments.TRANSCRIPT))
    }
}
