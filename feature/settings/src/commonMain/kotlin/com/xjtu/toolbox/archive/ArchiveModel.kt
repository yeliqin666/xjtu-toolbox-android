package com.xjtu.toolbox.archive

/** 统一存档层的数据模型。 */

/** §2.1：档案顶层的 `format` 固定值。 */
const val ARCHIVE_FORMAT = "xjtu-toolbox-archive"

/** §2.2：每个 store 文件的 `format` 固定值。 */
const val ARCHIVE_STORE_FORMAT = "xjtu-toolbox/store"

/** 本文件版本（§2.1 / §2.2）。不认识就明确失败。 */
const val ARCHIVE_VERSION = 1

/** 值的类型标签（§2.2 `t` ∈ s/b/i/l/f/set）。 */
sealed interface StoreValue {
    val tag: String
    data class S(val v: String) : StoreValue { override val tag get() = "s" }
    data class B(val v: Boolean) : StoreValue { override val tag get() = "b" }
    data class I(val v: Int) : StoreValue { override val tag get() = "i" }
    data class L(val v: Long) : StoreValue { override val tag get() = "l" }
    data class F(val v: Float) : StoreValue { override val tag get() = "f" }

    /** 字符串集合（§2.2）。 */
    data class SetValue(val v: List<String>) : StoreValue { override val tag get() = "set" }
}

/** 一个端内 store 的完整快照（§2.0 映射规则的左边 + §2.2 的 keys）。 */
data class StoreSnapshot(
    val rawName: String,
    val keys: Map<String, StoreValue>,
    val writtenAt: Map<String, Long> = emptyMap(),
)

/** `manifest.accounts[]` 的一项（§2.1）。 */
data class ArchiveAccount(
    val accountId: String,
    val suffix: String,
    val accountType: String,
    val lastUsedAt: Long,
)

/** `manifest.json`（§2.1）。 */
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

/** `files/` 的一事一文件（§2.3）。 */
data class ArchiveFile(
    val suffix: String,
    val relPath: String,
    val content: String,
)
