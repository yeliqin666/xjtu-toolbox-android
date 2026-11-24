package com.xjtu.toolbox.ss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 需定位签到：签到点解析与「人在不在范围内」的判据。 */
class SsSignRangeJvmTest {

    private val spot = 34.2468 to 108.9840

    @Test
    fun `签到点经纬度不管谁在前都认得出，认不出就是 null`() {
        assertEquals(spot, SsActivityApi.parseSignSpot("108.9840,34.2468"))
        assertEquals(spot, SsActivityApi.parseSignSpot("34.2468, 108.9840"))
        assertEquals(spot, SsActivityApi.parseSignSpot("""{"lng":"108.9840","lat":"34.2468"}"""))
        assertNull(SsActivityApi.parseSignSpot(""))
        assertNull(SsActivityApi.parseSignSpot("兴庆校区"))
    }

    @Test
    fun `范围内放行、范围外拦下`() {
        assertTrue(SsActivityApi.withinSignRange(34.2470 to 108.9842, spot, 100))
        // 往北约 1.1 公里
        assertFalse(SsActivityApi.withinSignRange(34.2568 to 108.9840, spot, 300))
    }

    @Test
    fun `签到点若是火星坐标，站在原地的 WGS-84 定位也算在场`() {
        val wgsHere = 34.2468 to 108.9840
        val gcjSpot = SsActivityApi.wgsToGcj(wgsHere)
        assertTrue(SsActivityApi.distanceMeters(wgsHere, gcjSpot) > 200, "两套坐标在西安应差几百米")
        assertTrue(SsActivityApi.withinSignRange(wgsHere, gcjSpot, 50))
    }
}
