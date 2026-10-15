package com.xjtu.toolbox.platform

import java.util.Base64

/** 给任意 [KeyValueStore] 套一层凭据保护：写下去的值先交给 [SecretProtector] 保护，读回来的值再解开。 */
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

/** `:data` 的密文存储 + 凭据保护器 的那一份。 */
fun protectedSecureKeyValueStore(
    name: String,
    legacyFallbackName: String? = null,
    protector: SecretProtector? = secretProtectorForPlatform(),
): KeyValueStore = ProtectedKeyValueStore(secureKeyValueStore(name, legacyFallbackName), protector)
