package com.xjtu.toolbox.archive

/** 账号命名空间后缀的纯函数（§4）。 */
object ArchiveNames {

    /** `safeSuffix()`（§4）。 */
    fun suffixFor(accountId: String?): String {
        val id = accountId ?: return "default"
        return "_" + id.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    /** 把端内名拆成 (base, suffix)，最长后缀优先（§1.4 / §2.0）。 */
    fun split(rawNames: List<String>, suffixes: List<String>): List<StoreId> {
        val byLength = suffixes.distinct().sortedByDescending { it.length }
        return rawNames.map { raw ->
            val hit = byLength.firstOrNull { s ->
                s.isNotEmpty() && raw.length > s.length && raw.endsWith(s)
            }
            if (hit == null) StoreId(raw, raw, "") else StoreId(raw, raw.dropLast(hit.length), hit)
        }
    }

    /** 检测 suffix 碰撞：返回发生碰撞的 suffix（升序，稳定）。 */
    fun detectCollision(accounts: List<ArchiveAccount>): List<String> =
        accounts.groupBy { suffixFor(it.accountId) }
            .filterValues { group -> group.map { it.accountId }.distinct().size > 1 }
            .keys
            .sorted()
}

/** 端内名拆分结果：§2.0 那条唯一映射规则的左边。 */
data class StoreId(val raw: String, val base: String, val suffix: String)
