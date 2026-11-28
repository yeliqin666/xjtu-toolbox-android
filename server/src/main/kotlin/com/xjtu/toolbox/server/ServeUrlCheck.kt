package com.xjtu.toolbox.server

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom

/**
 * 严格解析一个绝对 URL；畸形串 / 相对串 / 协议不完整的一律 `null`。
 *
 * 为什么单独放一处（2026-11-27 #597）：这一层有两处 SSRF 闸门要看 URL 的 host
 * （教材封面代理的 `?url=`、下载直链的 `url`），两处都**不能手工切字符串** ——
 * `https://evil.com#@jiaocai1.lib.xjtu.edu.cn/a.jpg` 这类构造能让手写的
 * `substringAfter('@')` 看到白名单域名、而实际请求打到别处（`#` 之后整段是 fragment）。
 * 手写一份就会错一次，所以只留这一个入口。
 *
 * `takeFrom` 是 Ktor 的**严格**解析（宽松的那个叫 `takeFromUnsafe`，别用），
 * 解析不了就抛 [io.ktor.http.URLParserException]，这里统一收成 `null`。
 *
 * 另外**含反斜杠的一律 `null`**：`https://evil.com\@jiaocai1.lib.xjtu.edu.cn/a.jpg` 这种，
 * Ktor 与 OkHttp（真正发请求的那一个）对 `\` 的读法并不一致 —— 判的那一层看到白名单、
 * 发的那一层看到别的主机，正是 #597 那一类绕过。合法直链里不会有反斜杠，直接拒最省事。
 */
internal fun parseAbsoluteUrl(raw: String): Url? {
    val trimmed = raw.trim()
    if (trimmed.contains('\\')) return null
    return runCatching { URLBuilder().takeFrom(trimmed).build() }.getOrNull()
}

/**
 * 把 `Url.host` 折算成「**真正发请求的那一层**（OkHttp）会看到的样子」；折算不了就 `null`。
 *
 * 为什么需要这一层（#597 复审）：Ktor 与 OkHttp 对主机里的百分号编码处理**不一样**。
 * Ktor 原样保留，OkHttp 解码后再发 —— 实测（okhttp 5.5.0）：
 * `http://%31%32%37.0.0.1:8080/` → host `127.0.0.1`，`http://127%2e0%2e0%2e1/` → `127.0.0.1`，
 * `http://%6c%6f%63%61%6c%68%6f%73%74/` → `localhost`。按 Ktor 的字符串判「是不是本机」，
 * 这几条都能过去（`%31…` 既不是 `localhost` 也不全是数字点），换到发送端就成了回环地址。
 *
 * 所以判之前先解码，再做三件事：
 * - 解完还带 `%`（`%25%33%31` 这种双层编码）或出现非 ASCII ⇒ 一律 `null`：合法主机名里不该有这些；
 * - **去掉尾随的点**：`localhost.` / `127.0.0.1.` 在 DNS 里与不带点的同一个主机，
 *   但按字符串比就绕过了「等于 localhost」那一条；
 * - 统一小写（[Url.host] 已经小写，这里只是兜底）。
 */
internal fun canonicalHostForRequest(rawHost: String): String? {
    val decoded = percentDecode(rawHost) ?: return null
    val host = decoded.trimEnd('.')
    if (host.isEmpty()) return null
    if (host.any { it == '%' || it.code > 0x7F }) return null
    return host.lowercase()
}

/** 只解码主机里出现的 `%XX` 三元组；畸形（截断 / 非十六进制）返回 `null`。 */
private fun percentDecode(raw: String): String? {
    if ('%' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        if (i + 2 >= raw.length) return null
        val hi = raw[i + 1].digitToIntOrNull(16) ?: return null
        val lo = raw[i + 2].digitToIntOrNull(16) ?: return null
        out.append(((hi shl 4) or lo).toChar())
        i += 3
    }
    return out.toString()
}
