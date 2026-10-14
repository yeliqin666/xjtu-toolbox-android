package com.xjtu.toolbox.archive

/**
 * 统一存档层的**数据模型**（S1，零端接线）。
 *
 * 形状真源 = `docs/storage-layout.md` §2：
 *  - §2.1 `manifest.json` 的字段与类型；
 *  - §2.2 `stores/<store>.json` 的带类型值 `{t, v, at}`；
 *  - §2.3 `files/` 原样搬运（值本身已是 AppJson 编出来的 JSON，不重新建模）。
 *
 * 这里只有纯数据与常量，没有任何 I/O、迁移或端内接线 —— 那些分属后续工单。
 */

/** §2.1：档案顶层的 `format` 固定值。 */
const val ARCHIVE_FORMAT = "xjtu-toolbox-archive"

/** §2.2：每个 store 文件的 `format` 固定值。 */
const val ARCHIVE_STORE_FORMAT = "xjtu-toolbox/store"

/** §2.1 / §2.2：本文件版本。不认识就明确失败（导入侧判，见 A7）。 */
const val ARCHIVE_VERSION = 1

/**
 * 值的类型标签（§2.2 `t` ∈ s/b/i/l/f/set）。
 *
 * 为什么必须带标签：Android 的 prefs 是有类型的（XML `<long>`/`<float>`/`<set>`），
 * Properties 与 localStorage 只有字符串 —— 没有标签就没法往返，`:core` 的
 * [com.xjtu.toolbox.platform.KeyValueStore] 只有 String/Boolean/Int/Set 四档，
 * 拿它当投影口会静默丢 `l`（int64）与 `f`（float）。
 */
sealed interface StoreValue {
    val tag: String
    data class S(val v: String) : StoreValue { override val tag get() = "s" }
    data class B(val v: Boolean) : StoreValue { override val tag get() = "b" }
    data class I(val v: Int) : StoreValue { override val tag get() = "i" }
    data class L(val v: Long) : StoreValue { override val tag get() = "l" }
    data class F(val v: Float) : StoreValue { override val tag get() = "f" }

    /**
     * 字符串集合（§2.2）。去重 + 升序由 codec 负责；**空数组合法且 ≠ 键不存在**。
     */
    data class SetValue(val v: List<String>) : StoreValue { override val tag get() = "set" }
}

/**
 * 一个端内 store 的完整快照（§2.0 映射规则的左边 + §2.2 的 keys）。
 *
 * @param rawName 端内名，**含后缀**（如 `schedule_cache_2253415556`）。
 * @param keys 该 store 的全部键值。键不在这里 = 键不存在（三端 `putString(key, null)` 都等于删键）。
 * @param writtenAt 旁挂时间戳（§5.2 第 5 条）：键 = 业务键名，值 = UTC epoch 毫秒。
 *   Android 普通 prefs 没有 mtime ⇒ 为空；桌面/web 的 `<key>#written_at` 由 codec 折进这里。
 */
data class StoreSnapshot(
    val rawName: String,
    val keys: Map<String, StoreValue>,
    val writtenAt: Map<String, Long> = emptyMap(),
)

/**
 * `manifest.accounts[]` 的一项（§2.1）。
 *
 * [accountId] 是**原值**（学号/手机号），用于发现 suffix 碰撞（A9）；
 * [suffix] 是 [ArchiveNames.suffixFor] 的逐字结果（含前导 `_`，null 时为 `default`）。
 */
data class ArchiveAccount(
    val accountId: String,
    val suffix: String,
    val accountType: String,
    val lastUsedAt: Long,
)

/**
 * `manifest.json`（§2.1）。`stores`/`files` 已按码点升序；`accounts` 保持宿主给的顺序。
 */
data class ArchiveManifest(
    val format: String = ARCHIVE_FORMAT,
    val version: Int = ARCHIVE_VERSION,
    val createdAt: String,
    val createdBy: String,
    val appVersion: String,
    val noAccount: Boolean = false,
    val accounts: List<ArchiveAccount> = emptyList(),
    val stores: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val redacted: List<String> = emptyList(),
    val ttlClass: Map<String, String> = emptyMap(),
)

/**
 * `files/` 的一事一文件（§2.3）。
 *
 * @param suffix 该文件所属命名空间：`""` = `global/files/`，否则 `accounts/<suffix>/files/`。
 * @param relPath `files/` 之下的相对路径（如 `agent_sessions/index.json`），**目录名不带 suffix**。
 * @param content 原样搬运的内容（值本身已是 AppJson 编出的 JSON，不重新建模）。
 */
data class ArchiveFile(
    val suffix: String,
    val relPath: String,
    val content: String,
)
