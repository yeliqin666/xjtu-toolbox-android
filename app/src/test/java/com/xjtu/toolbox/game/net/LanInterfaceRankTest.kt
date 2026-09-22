package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanInterfaceRankTest {

    @Test
    fun `蜂窝和 VPN 网卡不能进二维码`() {
        listOf("rmnet_data0", "ccmni1", "tun0", "ppp0", "p2p-wlan0-0", "clat4", "dummy0").forEach {
            assertNull(it, lanInterfaceRank(it))
        }
    }

    @Test
    fun `Wi-Fi 优先于热点，热点优先于有线`() {
        assertEquals(0, lanInterfaceRank("wlan0"))
        assertTrue(lanInterfaceRank("wlan0")!! < lanInterfaceRank("ap0")!!)
        assertTrue(lanInterfaceRank("swlan0")!! < lanInterfaceRank("eth0")!!)
    }
}
