package com.xjtu.toolbox.archive

/**
 * 导入档位选择的那一份话术（§6.4「显式镜像档」）—— **三端逐字同一句**。
 *
 * 放在共享层是为了让 Android / 桌面 / web 不可能各写一套：三端只负责把
 * 标题 + 档位说明 + 差异那一行摆进各自的弹窗里。
 */
object ArchiveImportPrompt {

    /** 弹窗标题。 */
    const val TITLE: String = "导入方式"

    /** 默认档的选项名。 */
    const val MERGE_LABEL: String = "只补上本机没有的（默认）"

    /** 默认档的说明。 */
    const val MERGE_DETAIL: String =
        "本机已有的偏好、收藏、缓存一概不动；档案里有、本机没有的照写。"

    /** 镜像档的选项名。 */
    const val MIRROR_LABEL: String = "以档案为准（镜像）"

    /** 镜像档的说明。 */
    const val MIRROR_DETAIL: String =
        "档案里出现过的 store 按档案对齐：档案里没有的键会被删掉；" +
            "档案里没出现的 store（含账号相关）一个字不动；凭据、登录态等红线项永不删。"

    /** 镜像档那块「不做后悔药」的交代（用户已明确接受：覆盖前不自动备份）。 */
    const val NO_BACKUP: String = "覆盖前不会自动备份，删掉的数据在本机找不回来。"

    /** 确认键。 */
    const val CONFIRM: String = "确认导入"

    /** 取消键。 */
    const val CANCEL: String = "取消"

    /** 档位 → 选项名。 */
    fun label(mode: ArchiveImportMode): String = when (mode) {
        ArchiveImportMode.MERGE -> MERGE_LABEL
        ArchiveImportMode.MIRROR -> MIRROR_LABEL
    }

    /** 档位 → 说明。 */
    fun detail(mode: ArchiveImportMode): String = when (mode) {
        ArchiveImportMode.MERGE -> MERGE_DETAIL
        ArchiveImportMode.MIRROR -> MIRROR_DETAIL
    }

    /**
     * 差异那一行：「将删除 N 个键、覆盖 M 个键、新写入 K 个键」。
     *
     * 默认档不出现「删除」二字 —— 它压根不会删（用户看一眼就知道自己选的是哪一档）。
     */
    fun diffSummary(mode: ArchiveImportMode, diff: ArchiveImportDiff): String = buildString {
        if (mode == ArchiveImportMode.MIRROR) {
            append("将删除 ")
            append(diff.deletes)
            append(" 条、覆盖 ")
        } else {
            append("将覆盖 ")
        }
        append(diff.overwrites)
        append(" 条、新写入 ")
        append(diff.keysAdded + diff.filesAdded)
        append(" 条")
        if (diff.keysProtected > 0) {
            append("；另有 ")
            append(diff.keysProtected)
            append(" 个键在红线清单上，保留不动")
        }
        if (diff.filesSkipped > 0) {
            append("；")
            append(diff.filesSkipped)
            append(" 条档案条目转不到本机，跳过")
        }
    }

    /** 镜像档落地前的最后一句提醒；默认档 ⇒ null（没什么好提醒的）。 */
    fun warning(mode: ArchiveImportMode, diff: ArchiveImportDiff): String? = when {
        mode != ArchiveImportMode.MIRROR -> null
        diff.quiet -> "本机与这份档案在档案声明过的范围内已经一致，导入不会改动什么。"
        else -> NO_BACKUP
    }
}
