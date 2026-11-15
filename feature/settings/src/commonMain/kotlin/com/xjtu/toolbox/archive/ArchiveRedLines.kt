package com.xjtu.toolbox.archive

/**
 * 镜像档的红线清单（§6.4 逐字）：这些店无论档案长什么样，**一概不删**，只可能被写。
 *
 * 判断对象是店的 **base 名**（去掉账号后缀的那个名字），与 [ArchiveRegistry] 里的键同一套写法。
 * 保守口径：清单按「宁可留着」写 —— 多留一个键只占几字节，误删一个凭据就是重新登录一遍。
 */
object ArchiveRedLines {

    /** 逐字名单（§6.4）。 */
    private val exactNames: Set<String> = setOf(
        "xjtu_credentials",
        "xjtu_accounts",
        "xjtu_cookies",
        "github_community",
        "serve",
        "data_cache_meta",
    )

    /** 前缀匹配：`cookies_*` / `sites_*` / `agent_config_secure*`。 */
    private val prefixes: List<String> = listOf("cookies_", "sites_", "agent_config_secure")

    /** 后缀匹配：`*_fallback`（Android 侧 `<store>_fallback` 那份明文残留同名同形）。 */
    private val suffixes: List<String> = listOf("_fallback")

    /** 这个店的键是不是「永不删」：是 ⇒ 镜像档对它只写不删。 */
    fun isProtected(base: String): Boolean =
        base in exactNames ||
            prefixes.any { base.startsWith(it) } ||
            suffixes.any { base.endsWith(it) }

    /** 逐字名单全表（守卫测试读它，防止有人悄悄改窄）。 */
    fun allNames(): List<String> = exactNames.sorted()

    /** 前缀 / 后缀全表（守卫测试读它）。 */
    fun allPatterns(): List<String> = (prefixes + suffixes).sorted()
}
