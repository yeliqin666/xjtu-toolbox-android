package com.xjtu.toolbox.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class AgentWebAddressTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun blocksPrivateAndSpecialRanges() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.1.1", "0.0.0.0", "100.64.0.1", "224.0.0.1",
            "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "::ffff:192.168.0.1",
        ).forEach { assertTrue(it, AgentWeb.isNonPublicAddress(ip(it))) }
    }

    @Test
    fun allowsPublicAddresses() {
        listOf("202.117.0.20", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888")
            .forEach { assertFalse(it, AgentWeb.isNonPublicAddress(ip(it))) }
    }

    @Test
    fun urlPrecheckCatchesLiteralsButLeavesDomainsToSocketGuard() {
        assertTrue(AgentWeb.isBlockedHost("localhost"))
        assertTrue(AgentWeb.isBlockedHost("127.0.0.1"))
        assertTrue(AgentWeb.isBlockedHost("[::1]"))
        assertTrue(AgentWeb.isBlockedHost("[::ffff:10.0.0.1]"))
        assertFalse(AgentWeb.isBlockedHost("example.com"))
        assertFalse(AgentWeb.isBlockedHost("8.8.8.8"))
    }

    @Test(expected = java.io.IOException::class)
    fun socketGuardRefusesLoopbackBeforeConnecting() {
        AgentWeb.publicOnlySocketFactory.createSocket().use {
            it.connect(InetSocketAddress(ip("127.0.0.1"), 9), 1000)
        }
    }
}
