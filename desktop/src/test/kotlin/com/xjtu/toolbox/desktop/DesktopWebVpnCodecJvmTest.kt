package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.webvpn.WebVpnCodec
import com.xjtu.toolbox.webvpn.WebVpnUrl
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebVPN 转换器（`AppRoute.WebVpnConverter`，第 28 条真数据路由）在桌面端的**装配验收**。
 *
 * 这一条路由的屏（`:core` 的 `WebVpnConverterScreen`）本轮才搬完 —— 搬迁前它在 `:app`，桌面只能落
 * `NotPortedScreen`。屏本身只认 `:core` 的 [WebVpnCodec] 缝（那两个方向的转换本体要用
 * `javax.crypto` 的 AES-128-CFB，进不了 `:core` 的 commonMain），这一端注入的是 `:data` 的
 * [WebVpnUtil] —— **与 Android 同一份实现**（不是另写一份）。
 *
 * 所以「桌面真能转」这条验收，判据就是这份实现的两个方向都对：
 *
 *  1. **正向**：普通网址 → 网关地址，形状逐段对（`https://webvpn.xjtu.edu.cn/<proto>[-port]/<iv><密文域名>/<path>`），
 *     且密文域名那一段是 AES-128-CFB（key/iv 都是 `wrdvpnisthebest!`）算出来的 32 个十六进制 IV
 *     + 域名密文 —— 这里钉住**定值**（换个算法/换 key 就会红，那正是「转换本体」这一件事）；
 *  2. **反向**：把网关地址解回原网址，host / 端口 / 路径 / 查询 / 片段一个不丢；
 *  3. **已经是网关地址的输入原样返回**（与 `WebVpnUtil.getVpnUrl` 同口径），解析不了的返回 null。
 *
 * ⚠️ 夹具里的域名是**真域名**（`jwxt.xjtu.edu.cn` 这类公开站点名），但没有任何凭据/账号：
 * 这一段是纯字符串运算，一条请求都不发（`WebVpnConverterScreen` 的 KDoc 也是这么写的）。
 */
class DesktopWebVpnCodecJvmTest {

    /** 屏认的那条缝，桌面注入的就是它 —— 顺带证明 `:data` 的对象确实实现了 `:core` 的接口。 */
    private val codec: WebVpnCodec = WebVpnUtil

    @Test
    fun `正向：教务首页转成网关地址，密文域名那一段是定值`() {
        val vpn = codec.toVpnUrl("https://jwxt.xjtu.edu.cn/")

        // 形状：https://webvpn.xjtu.edu.cn/<protocol>/<iv_hex(32)><encrypted_domain_hex>/<rest>
        val prefix = "https://webvpn.xjtu.edu.cn/https/77726476706e69737468656265737421"
        assertTrue(vpn.startsWith(prefix), "网关地址前缀不对：$vpn")
        // 定值：AES-128-CFB（iv = key = `wrdvpnisthebest!`）加密 "jwxt.xjtu.edu.cn" 的结果。
        // 这个值不是手算的：把 `WebVpnUtil.cfb128Encrypt` 那段循环逐行搬到 javax.crypto 上跑一遍
        // 得到 `fae05988692862446b468ca88d1b203b`（与 openssl 的 `-aes-128-cfb` 逐字节一致）。
        // 换算法、换 key/iv、或者把 CFB 的反馈写错，这一行就会红 —— 那正是「转换本体」。
        assertEquals(
            "$prefix" + "fae05988692862446b468ca88d1b203b" + "/",
            vpn,
        )
    }

    @Test
    fun `正向：带端口、路径、查询、片段都搬到网关地址上`() {
        val vpn = codec.toVpnUrl("http://rg.lib.xjtu.edu.cn:8086/seat/?a=1#top")
        assertTrue(vpn.startsWith("https://webvpn.xjtu.edu.cn/http-8086/"), "端口要编进那一段：$vpn")
        assertTrue(vpn.endsWith("/seat/?a=1#top"), "路径/查询/片段要原样接在后面：$vpn")
    }

    @Test
    fun `反向：网关地址解回原网址，host 与端口一个不丢`() {
        val originals = listOf(
            "https://jwxt.xjtu.edu.cn/",
            "https://www.lib.xjtu.edu.cn/",
            "http://rg.lib.xjtu.edu.cn:8086/seat/",
            "https://kq.xjtu.edu.cn/student-pc",
            "https://kq.xjtu.edu.cn/student-pc?date=2026-10-08#top",
        )
        originals.forEach { original ->
            val vpn = codec.toVpnUrl(original)
            // 比到「路径结尾的那个 /」为止：反向解出来的路径不再多一个空段
            //（`WebVpnUtil.getOriginalUrl` 拿 `split("/", limit = 3)` 拼回去，只有根路径时那个
            // 尾斜杠会掉 —— 这是**搬迁前就有的口径**，本轮一个字符都没改，所以这里按实际行为钉住）。
            assertEquals(
                original.trimEnd('/'),
                codec.fromVpnUrl(vpn)?.trimEnd('/'),
                "来回一趟应当回到原网址：$original",
            )
        }
    }

    @Test
    fun `已经是网关地址的输入原样返回；解析不了的返回 null`() {
        val already = codec.toVpnUrl("https://jwxt.xjtu.edu.cn/")
        assertEquals(already, codec.toVpnUrl(already))

        // 不是网关域名 ⇒ 反向解不出来（与 `WebVpnUtil.getOriginalUrl` 同口径）
        assertNull(codec.fromVpnUrl("https://jwxt.xjtu.edu.cn/"))
        // 是网关域名但路径不成形 ⇒ 也是 null（不抛）
        assertNull(codec.fromVpnUrl("https://webvpn.xjtu.edu.cn/"))
    }

    @Test
    fun `判据那一半在 core：网关域名认得出来，别的域名不认`() {
        // `:core` 的 `WebVpnUrl`（三端共用的纯判据）与上面那份实现必须指向同一个域名
        assertTrue(WebVpnUrl.isWebVpnUrl(codec.toVpnUrl("https://jwxt.xjtu.edu.cn/")))
        assertTrue(!WebVpnUrl.isWebVpnUrl("https://jwxt.xjtu.edu.cn/"))
    }
}
