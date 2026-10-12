package com.xjtu.toolbox.platform

import java.util.Base64

/**
 * 给任意 [KeyValueStore] 套一层**凭据保护**：写下去的值先交给 [SecretProtector] 保护，
 * 读回来的值再解开。
 *
 * ## 落盘格式（这份格式就是兼容性契约）
 *
 * | 盘上的值 | 谁写的 | 读的时候 |
 * |---|---|---|
 * | `xjtu-sealed:1:<保护器 id>:<base64(protect(明文))>` | 有保护器的机器（Windows） | 信封 id 与当前保护器**不同就抛**；相同才解 |
 * | 就是明文本身 | 没有保护器的机器（Linux/macOS，今天的行为）、或这一轮之前的文件 | 原样返回 |
 *
 * 第一行里那三个部分各有用处：
 *  - `xjtu-sealed:1:` —— 「这是一个信封」的判据，同时也是**版本号**（将来换格式时它加一）；
 *  - `<保护器 id>` —— 写它的是谁保护的。换用户 / 换机器 / 把数据目录从 Windows 拷到 Linux，
 *    都会在这里被发现并**明确报错**，而不是把一串密文当密码交出去（见下）；
 *  - `base64` —— DPAPI 的密文是任意字节，而这份存储的文件是 `java.util.Properties`
 *    （值会被转义 —— 连信封里的 `:` 也会被写成 `\:`，那是文件层面的事，读回来还是冒号；
 *    但如果直接塞二进制进值里，就得赌转义对不对，没必要）。
 *
 * ## 为什么明文那一档**原样**落盘（而不是也套个信封）
 *
 * 因为 Linux 上今天就是明文 `0600` 文件，而这一轮**不许**改变它的字节：老文件要能读
 *（`:desktop` 的 `DesktopAuthLibraryJvmTest` 里就有「手工写一份老明文凭据再让它读」的用例），
 * 而且不能为了「格式好看」让没受保护的平台多一层看不懂的包装。信封只在真的保护了字节时才写。
 *
 * ## 读一份**别人**信封装的值：抛，不是返回密文
 *
 * 这一条是这份存储最重要的语义，三个测试都钉它：信封 id 与当前保护器不一致（换用户/换机器/
 * 跨平台复制），或者本机根本没有保护器（`protector == null` 而文件里却有信封），
 * 一律抛 [SecretProtectionException]。**绝不**返回信封原文（那会把 `xjtu-sealed:1:...` 这串
 * 当成密码去登录），也**绝不**当「没有这个键」静默丢掉（那会让「密码丢了」表现得像「第一次登录」）。
 *
 * ## 键不存在 / 坏文件
 *
 * 与 [KeyValueStore] 的通用口径一致：键不存在返回 `null` / 默认值；`getStringSet` 的
 * 「空集合」与「没有这个键」仍是两件事（空集合落盘是空串，读回来是空集合）。
 * 布尔与整数档在这里是**字符串的语法糖**（与 [FileKeyValueStore] 逐字同口径），所以它们也一并受保护。
 */
internal class ProtectedKeyValueStore(
    private val delegate: KeyValueStore,
    private val protector: SecretProtector?,
) : KeyValueStore {

    override fun getString(key: String): String? = delegate.getString(key)?.let(::open)

    override fun putString(key: String, value: String?) = delegate.putString(key, value?.let(::seal))

    override fun getBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun getInt(key: String, default: Int): Int = getString(key)?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    override fun getStringSet(key: String): Set<String>? = getString(key)?.let { raw ->
        raw.split('\u0000').filter { it.isNotEmpty() }.toSet()
    }

    override fun putStringSet(key: String, value: Set<String>) = putString(key, value.joinToString("\u0000"))

    override fun remove(key: String) = delegate.remove(key)

    override fun contains(key: String): Boolean = delegate.contains(key)

    override fun clear() = delegate.clear()

    /** 明文 → 盘上的值。**没有保护器就原样写**（Linux 的现状；见类 KDoc 第二段）。 */
    private fun seal(value: String): String {
        val local = protector ?: return value
        val sealed = try {
            local.protect(value.encodeToByteArray())
        } catch (e: Exception) {
            throw SecretProtectionException("「${local.id}」保护不了这个值，本次写入失败：$e", e)
        }
        return ENVELOPE_PREFIX + local.id + ID_SEPARATOR + Base64.getEncoder().encodeToString(sealed)
    }

    /** 盘上的值 → 明文。明文原样通过；信封要么解开、要么抛。 */
    private fun open(stored: String): String {
        if (!stored.startsWith(ENVELOPE_PREFIX)) return stored
        val rest = stored.removePrefix(ENVELOPE_PREFIX)
        val separator = rest.indexOf(ID_SEPARATOR)
        if (separator <= 0) {
            throw SecretProtectionException("这份凭据的信封坏了（没有保护器 id）：${rest.take(32)}…")
        }
        val id = rest.substring(0, separator)
        val payload = rest.substring(separator + 1)
        val local = protector
        if (local == null || local.id != id) {
            val mine = if (local == null) "本机没有凭据保护器" else "本机的保护器是「${local.id}」"
            throw SecretProtectionException(
                "这份凭据由「$id」保护，但$mine ⇒ 解不开。" +
                    "换用户 / 换机器 / 把数据目录跨平台复制过来都会这样，这是保护本身在生效：请重新登录。",
            )
        }
        val sealed = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw SecretProtectionException("这份凭据的信封不是合法 base64：$e", e)
        }
        return try {
            local.unprotect(sealed).decodeToString()
        } catch (e: Exception) {
            throw SecretProtectionException(
                "「$id」解不开这份凭据（密文被改过，或用的不是同一台机器的同一个用户）：$e",
                e,
            )
        }
    }

    private companion object {

        /** 信封前缀。**格式版本号写在这里**（`xjtu-sealed:1:` ⇒ 将来换格式就是 `:2:`）。 */
        const val ENVELOPE_PREFIX = "xjtu-sealed:1:"

        /** 前缀之后紧跟保护器 id，用冒号与 base64 载荷分开。 */
        const val ID_SEPARATOR = ':'
    }
}
