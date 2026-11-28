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
