package com.xjtu.toolbox.archive

/**
 * 一次导入的差异（三端弹窗就读这一个）。
 *
 * 数字是**算出来的**（[StoreArchive.plan]），不是落完地之后的统计 —— 用户看到的是「将要发生什么」。
 */
data class ArchiveImportDiff(
    /** 档案有、本端没有的键数（新增）。 */
    val keysAdded: Int = 0,
    /** 双方都有、会被档案改掉的键数（默认档只在档案更新时；镜像档只要值不同就算）。 */
    val keysOverwritten: Int = 0,
    /** 双方都有、按当前档位不动它的键数（默认档：本端胜 / 值相同也照写，算覆盖）。 */
    val keysKept: Int = 0,
    /** 镜像档要删掉的键数（档案里没有、本端有、且不在红线清单上）。 */
    val keysDeleted: Int = 0,
    /** 镜像档**挡下来**的删除数（本端有、档案没有，但那个店在红线清单上）。 */
    val keysProtected: Int = 0,
    /** `files/` 里要新写的条目数。 */
    val filesAdded: Int = 0,
    /** `files/` 里要改写的条目数（镜像档：本端这一条与档案不同）。 */
    val filesOverwritten: Int = 0,
    /** `files/` 里本端已有、按当前档位不动它的条目数。 */
    val filesUnchanged: Int = 0,
    /** 镜像档要删掉的 `files/` 条目数（档案里出现过的族里、本端多余的）。 */
    val filesDeleted: Int = 0,
    /** 拼不出端内名 / 转不动形状 / 读不出来的条目数（§6.3 残尾，一条都没写）。 */
    val filesSkipped: Int = 0,
) {

    /** 会写进端内的键数。 */
    val keysWritten: Int get() = keysAdded + keysOverwritten

    /** 会写进端内的 `files/` 条目数。 */
    val filesWritten: Int get() = filesAdded + filesOverwritten

    /** 会删掉的条目总数（键 + 文件）。 */
    val deletes: Int get() = keysDeleted + filesDeleted

    /** 会改掉已有值的条目总数（键 + 文件）。 */
    val overwrites: Int get() = keysOverwritten + filesOverwritten

    /** 这一趟会动端内已有的东西吗（三端据此决定要不要先弹确认）。 */
    val destructive: Boolean get() = deletes > 0

    /** 一笔都不会写（档案与端内已经一致）。 */
    val quiet: Boolean get() = keysWritten == 0 && deletes == 0 && filesWritten == 0
}

/**
 * 算好差异、**端内还没动**的一步。
 *
 * 三端同一套两步流：`StoreArchive.plan(...)` → 把 [diff] 给用户看 → 用户确认 → `StoreArchive.apply(plan, target)`。
 * 构造是 `internal`：外面只能从 [StoreArchive.plan] 拿到它，拿不到「手搓一份差异」这条路。
 */
class ArchiveImportPlan internal constructor(
    /** 这份差异是按哪个档位算的。 */
    val mode: ArchiveImportMode,
    /** 将发生什么（三端弹窗就显示 [summary]）。 */
    val diff: ArchiveImportDiff,
    internal val steps: List<ArchiveImportStep>,
) {

    /** 这一趟会删端内已有的数据吗（只有镜像档可能为 true）。 */
    val destructive: Boolean get() = diff.destructive

    /** 共享的那句差异话术（三端逐字相同，见 [ArchiveImportPrompt]）。 */
    val summary: String get() = ArchiveImportPrompt.diffSummary(mode, diff)
}

/** [StoreArchive.plan] 的结论。 */
sealed interface ArchivePlanOutcome {

    /** 差异算好了（端内一个字节都没动）。 */
    data class Ok(val plan: ArchiveImportPlan) : ArchivePlanOutcome

    /** 这份档案本身不成立（缺 `manifest.json` / 版本不认识 / 某个店解不开 / 后缀碰撞）；端内零改动。 */
    data class Failed(val reason: String) : ArchivePlanOutcome
}

/** 一次导入要落的**一笔**（[StoreArchive.plan] 算出来，[StoreArchive.apply] 才执行）。 */
internal sealed interface ArchiveImportStep {

    /** 写/覆盖一个键（[at] 只在该店有旁挂时间戳时非空）。 */
    data class PutKey(
        val rawName: String,
        val key: String,
        val value: StoreValue,
        val at: Long?,
    ) : ArchiveImportStep

    /** 删掉端内一个键（只有镜像档会产生这一笔）。 */
    data class RemoveKey(val rawName: String, val key: String) : ArchiveImportStep

    /** 写一条 `files/`（[content] 已经是本端形状，§6.3）。 */
    data class PutFile(val suffix: String, val relPath: String, val content: String) : ArchiveImportStep

    /** 删掉端内一条 `files/`（只有镜像档会产生这一笔）。 */
    data class RemoveFile(val suffix: String, val relPath: String) : ArchiveImportStep
}

/** 回滚用的反向操作（[StoreArchive.apply] 每一笔动之前先记一份「原来是什么」）。 */
internal sealed interface ArchiveUndo {

    /** 把键恢复成原来的值与原来的年龄。 */
    data class RestoreKey(
        val rawName: String,
        val key: String,
        val value: StoreValue,
        val at: Long?,
    ) : ArchiveUndo

    /** 这个键本来是**没有**的 ⇒ 回滚就是删掉它。 */
    data class RemoveKey(val rawName: String, val key: String) : ArchiveUndo

    /** 把一条 `files/` 恢复成原来的字节。 */
    data class RestoreFile(val suffix: String, val relPath: String, val content: String) : ArchiveUndo

    /** 这条 `files/` 本来是**没有**的 ⇒ 回滚就是删掉它。 */
    data class RemoveFile(val suffix: String, val relPath: String) : ArchiveUndo
}
