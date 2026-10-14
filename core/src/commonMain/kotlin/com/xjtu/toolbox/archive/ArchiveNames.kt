package com.xjtu.toolbox.archive

/**
 * 账号命名空间后缀的**纯函数**（§4 / A9）。
 *
 * 规则逐字照 `core/.../account/AccountContext.suffixFor`（它是三端唯一的后缀真源）；
 * 这里不 new 一份新规则，只把它可断言化，并补上「端内名 ↔ base+suffix 拆分」与
 * 「suffix 碰撞检测」（两个 accountId 落在同一 suffix 必须能被检测，A9）。
 */
object ArchiveNames {

    /**
     * `safeSuffix()` 逐字（§4）：
     *  - `null` → `"default"`（**无前导下划线**）；
     *  - `""` → `"_"`（空串不是 null，进替换分支，结果是单个下划线）；
     *  - 非字母数字**逐字符**换 `_`（不是整串替换），所以 `"a-b"` → `"_a_b"`。
     *
     * 它是多对一的：`"a-b"` 与 `"a_b"` 都得到 `"_a_b"` ⇒ 导入端必须检测并报错（见 [detectCollision]）。
     */
    fun suffixFor(accountId: String?): String {
        val id = accountId ?: return "default"
        return "_" + id.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    /**
     * 把端内名拆成 (base, suffix)，**最长后缀优先**（§1.4 / §2.0）。
     *
     * 匹配不上任何 suffix ⇒ 视为全局档（suffix = `""`）。suffix 含 `_`（匿名档）与
     * `default`（未登录档），所以用「后缀列表」而不是「是否以 `_` 开头」来判。
     */
    fun split(rawNames: List<String>, suffixes: List<String>): List<StoreId> {
        val byLength = suffixes.distinct().sortedByDescending { it.length }
        return rawNames.map { raw ->
            val hit = byLength.firstOrNull { s ->
                s.isNotEmpty() && raw.length > s.length && raw.endsWith(s)
            }
            if (hit == null) StoreId(raw, raw, "") else StoreId(raw, raw.dropLast(hit.length), hit)
        }
    }

    /**
     * 检测 suffix 碰撞：返回**发生碰撞的 suffix**（升序，稳定）。
     *
     * 空列表 = 无碰撞；非空 = 有两个不同 accountId 映射到同一 suffix（§4「必须明确失败」，
     * 由导入侧的 `SUFFIX_COLLISION` 落地，见 A9）。
     *
     * 用 [suffixFor] 从 **accountId 原值**重推 suffix（而非信 [ArchiveAccount.suffix] 字段）——
     * 碰撞的本质是 `suffixFor` 多对一（§4：「accountId 为原值，用于发现 suffix 碰撞」）。
     */
    fun detectCollision(accounts: List<ArchiveAccount>): List<String> =
        accounts.groupBy { suffixFor(it.accountId) }
            .filterValues { group -> group.map { it.accountId }.distinct().size > 1 }
            .keys
            .sorted()
}

/** 端内名拆分结果：§2.0 那条唯一映射规则的左边。 */
data class StoreId(val raw: String, val base: String, val suffix: String)
