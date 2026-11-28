package com.xjtu.toolbox.game.net

import com.xjtu.toolbox.platform.secureRandomInt

/**
 * 握手的口令证明：**口令本身一次都不上线**，双方各自拿它当密钥算一段证明给对方验。
 *
 * 为什么必须这么做（不是过度设计）：
 * 1. 加入方只按服务 UUID 找房主，而这个 UUID 房主正在公开广播——附近任何人伪造一个同 UUID
 *    的外设就能被选中。如果加入方在 hello 里发明文口令，就等于把口令送给"第一个连上的设备"，
 *    对方拿着它去连真房主、冒充对手，一次性消费也拦不住这一次（第一次永远是有效的）。
 * 2. BLE 没有配对就没有链路加密，明文口令本来就是广播着走的。
 *
 * 四步走，两边的证明都必须是"对着**对方刚给的随机数**算的"，否则抓包重放就能冒名顶替：
 *
 * | 步 | 方向 | 内容 | 这一步证明什么 |
 * |----|------|------|----------------|
 * | 1 | 加入方 → 房主 | `hello(nonceG, game)` | 只是打招呼，不含任何凭据 |
 * | 2 | 房主 → 加入方 | `challenge(nonceH, HMAC(口令,"chal"…))` | 房主知道口令（加入方据此排掉假外设） |
 * | 3 | 加入方 → 房主 | `auth(HMAC(口令,"auth"…))` | 加入方知道口令，且用的是**房主刚给的 nonceH** |
 * | 4 | 房主 → 加入方 | `hello_ack(HMAC(口令,"ack"…), rule, hostFirst)` | 对局参数确实出自房主，没被中间人改过 |
 *
 * 第 3 步之所以必须绑 nonceH：如果加入方只用自己的 nonce 自证，任何人抓到一份完整 `hello`
 * 都能原样重放给真房主，在口令被消费之前抢到那个位置。第 4 步之所以把 `rule`/`hostFirst`
 * 写进证明：中继可以原样转发证明但改参数，加入方会照着被改过的盘面开局。
 *
 * 剩下的边界：
 * - 口令熵只有 [OneTimeToken.generate] 那么长，抓到握手的人可以离线爆破（60 bit 是目前的答案）；
 * - 只能防"冒名"和"改参数"，防不了全程中继（中继可以把对局中的每一手都改掉）——要彻底解决
 *   得绑链路（BLE 配对 / LE Secure Connections）或者逐条消息加 MAC，是另一条线。
 */
object HandshakeAuth {
    private const val NONCE_BYTES = 16
    private const val HEX = "0123456789abcdef"

    /** 一次性随机数（16 字节十六进制）。每局每次握手都不一样，证明靠它防重放。 */
    fun newNonce(): String = buildString(NONCE_BYTES * 2) {
        repeat(NONCE_BYTES) {
            val b = secureRandomInt(256)
            append(HEX[b shr 4])
            append(HEX[b and 0x0F])
        }
    }

    /** 第 2 步：房主证明自己知道口令。绑定加入方的 nonce，别人抄走也重放不到另一次握手。 */
    fun challengeProof(token: String, guestNonce: String, hostNonce: String): String =
        hmacHex(token, "chal" + field(guestNonce) + field(hostNonce))

    /**
     * 第 3 步：加入方证明自己知道口令。必须绑 [hostNonce]——它是房主刚给的、抓包的人猜不到的，
     * 所以一份抓到的 `auth` 重放不出第二次。
     */
    fun authProof(token: String, guestNonce: String, hostNonce: String, game: String): String =
        hmacHex(token, "auth" + field(guestNonce) + field(hostNonce) + field(game))

    /**
     * 第 4 步：房主确认对局参数。`rule`/`hostFirst` 直接进 MAC，中继能转发证明但改不了参数——
     * 改了加入方就算不出同样的值，会当场断开而不是按被改过的盘面开局。
     */
    fun ackProof(
        token: String,
        guestNonce: String,
        hostNonce: String,
        rule: String?,
        hostFirst: Boolean,
    ): String = hmacHex(
        token,
        "ack" + field(guestNonce) + field(hostNonce) + field(rule) + field(if (hostFirst) "1" else "0"),
    )

    /**
     * 带长度前缀的字段编码：`|长度:内容`。不加前缀的话 `rule` 里只要出现分隔符，
     * 两组不同的参数就能编出同一串待签名数据（`{"a":"1|2"}` 与 `{"a":"1","b":"2"}` 这种）。
     */
    private fun field(value: String?): String = if (value == null) "|-1:" else "|${value.length}:$value"

    /**
     * 定长比较两段证明。用逐字节异或而不是 `==`，是为了不让"前几位对上"这件事从耗时里漏出去
     * ——口令只有几十 bit，能测出前缀匹配就等于把爆破砍成一棵可以逐位走的树。
     */
    fun sameProof(expected: String, candidate: String?): Boolean {
        if (candidate == null || candidate.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor candidate[i].code)
        return diff == 0
    }

    private fun hmacHex(key: String, message: String): String =
        HmacSha256.mac(key.encodeToByteArray(), message.encodeToByteArray()).toHex()
}

internal fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(hex[v shr 4])
            append(hex[v and 0x0F])
        }
    }
}

/**
 * HMAC-SHA256（RFC 2104 + FIPS 180-4）。
 *
 * 为什么带一份自己的 SHA-256 而不是 expect/actual 接平台实现：这个模块是 commonMain，
 * 三端（Android / JVM / wasmJs）都要用；平台侧要么只有 JVM 有 MessageDigest，要么在 wasm
 * 上得走异步 WebCrypto，为一个握手的短消息铺三条 actual 不值当。纯 Kotlin 一份实现、
 * 只用标准测试向量就能验（见 HandshakeAuthTest），也便于在 app 的单测里直接跑。
 */
object Sha256 {
    /** 前 64 个素数立方根小数部分取前 32 位（FIPS 180-4 §4.2.2）。 */
    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    )

    fun hash(input: ByteArray): ByteArray {
        // 补位：0x80 + 若干 0，末尾 8 字节是消息长度（bit），整体补齐到 64 字节的整数倍。
        val bitLength = input.size.toLong() * 8
        val total = ((input.size + 9 + 63) / 64) * 64
        val msg = ByteArray(total)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[total - 1 - i] = (bitLength ushr (8 * i)).toByte()

        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val w = IntArray(64)
        var offset = 0
        while (offset < total) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((msg[j].toInt() and 0xFF) shl 24) or
                    ((msg[j + 1].toInt() and 0xFF) shl 16) or
                    ((msg[j + 2].toInt() and 0xFF) shl 8) or
                    (msg[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val x = w[i - 15]
                val y = w[i - 2]
                val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
                val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = h + s1 + ch + K[i].toInt() + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        for ((index, value) in intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).withIndex()) {
            out[index * 4] = (value ushr 24).toByte()
            out[index * 4 + 1] = (value ushr 16).toByte()
            out[index * 4 + 2] = (value ushr 8).toByte()
            out[index * 4 + 3] = value.toByte()
        }
        return out
    }
}

/** HMAC-SHA256，块长 64 字节。 */
object HmacSha256 {
    private const val BLOCK_SIZE = 64

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val normalized = if (key.size > BLOCK_SIZE) Sha256.hash(key) else key
        val padded = ByteArray(BLOCK_SIZE).also { normalized.copyInto(it) }
        val inner = ByteArray(BLOCK_SIZE) { (padded[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK_SIZE) { (padded[it].toInt() xor 0x5c).toByte() }
        return Sha256.hash(outer + Sha256.hash(inner + message))
    }
}
