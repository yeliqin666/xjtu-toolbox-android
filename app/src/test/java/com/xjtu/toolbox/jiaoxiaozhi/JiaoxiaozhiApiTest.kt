package com.xjtu.toolbox.jiaoxiaozhi

import org.junit.Assert.assertEquals
import org.junit.Test

class JiaoxiaozhiApiTest {
    @Test
    fun cleanText_removesReferenceMarkersAndNormalizesWhitespace() {
        val raw = "第一段 !!12!!  \n\n\n\n第二段!!3!!"

        assertEquals("第一段\n\n第二段", JiaoxiaozhiApi.cleanText(raw))
    }

    @Test
    fun unknownModel_fallsBackToDefault() {
        assertEquals(
            JiaoxiaozhiModels.DEFAULT_ID,
            JiaoxiaozhiModels.byId("unknown-model").id,
        )
    }
}
