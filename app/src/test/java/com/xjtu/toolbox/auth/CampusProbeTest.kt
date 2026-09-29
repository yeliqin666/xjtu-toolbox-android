package com.xjtu.toolbox.auth

import com.xjtu.toolbox.auth.CampusProbe.Server
import com.xjtu.toolbox.auth.CampusProbe.Signal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusProbeTest {

    /** 按给定顺序和间隔（毫秒）投递信号，返回结论。 */
    private fun run(vararg steps: Pair<Long, Signal>, grace: Long = 100): CampusProbe.Verdict = runBlocking {
        val ch = Channel<Signal>(Channel.UNLIMITED)
        val sender = launch { steps.forEach { (wait, s) -> delay(wait); ch.send(s) } }
        CampusProbe.decide(ch, probeCount = 3, graceMs = grace).also { sender.cancel() }
    }

    private val fail = Signal.Probe(false)

    @Test
    fun `任一探针连上就是校内，不等服务器`() {
        val v = run(0L to fail, 0L to Signal.Probe(true))
        assertTrue(v.onCampus)
        assertTrue(v.strong)
    }

    @Test
    fun `服务器说校内直接采用，探针挂了也不影响`() {
        val v = run(0L to Signal.Check(Server.ON))
        assertTrue(v.onCampus)
        assertTrue(v.strong)
    }

    @Test
    fun `服务器说校外但宽限内探针连上，按校内（IPv6、学校 VPN）`() {
        val v = run(0L to Signal.Check(Server.OFF), 20L to Signal.Probe(true), grace = 500)
        assertTrue(v.onCampus)
    }

    @Test
    fun `服务器说校外、宽限内探针没动静，不等探针超时直接判校外`() {
        val started = System.currentTimeMillis()
        val v = run(0L to Signal.Check(Server.OFF), 5_000L to Signal.Probe(true), grace = 100)
        assertFalse(v.onCampus)
        assertTrue(v.strong)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `没有服务器结论时，探针全失败是弱的校外`() {
        val v = run(0L to fail, 0L to fail, 0L to fail, 10L to Signal.Check(Server.REACHABLE))
        assertFalse(v.onCampus)
        assertFalse(v.strong)
        assertFalse(v.offline)
    }

    @Test
    fun `公网内网都不通是没网，不下结论`() {
        val v = run(0L to Signal.Check(Server.UNREACHABLE), 0L to fail, 0L to fail, 0L to fail)
        assertTrue(v.offline)
    }

    @Test
    fun `令牌按 JWT 的 exp 判过期，解不出来的交给服务器`() {
        fun jwt(payload: String) = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
        val token = jwt("""{"iat":1000,"exp":29800}""")
        assertFalse(CampusProbe.isExpired(token, 1000_000L))
        assertTrue(CampusProbe.isExpired(token, 29_800_000L))
        assertFalse(CampusProbe.isExpired("opaque-token", Long.MAX_VALUE))
    }

    @Test
    fun `解析 networkCheck`() {
        assertEquals(Server.ON, CampusProbe.parseCheck("""{"code":0,"message":null,"data":true}"""))
        assertEquals(Server.OFF, CampusProbe.parseCheck("""{"code":0,"data":false}"""))
        assertEquals(Server.ON, CampusProbe.parseCheck("""{"code":0,"data":1}"""))
        assertEquals(Server.REACHABLE, CampusProbe.parseCheck("""{"code":-1,"message":"没有访问权限01","data":null}"""))
        assertEquals(Server.REACHABLE, CampusProbe.parseCheck("<html>502</html>"))
    }
}
