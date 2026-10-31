package com.xjtu.toolbox.archive

/**
 * 导入档位（docs/storage-layout.md §6.4「显式镜像档」）。
 *
 * 用户在**导入那一刻显式选一次**；不选就还是默认档，口径与以前逐字相同。
 */
enum class ArchiveImportMode {

    /**
     * 默认档（`at` 新者胜 / 缺 `at` 本端胜 / 档案有本端没有就写）。
     *
     * **本端多余的键一个字不动** —— 这也是默认档与镜像档唯一的分界。
     */
    MERGE,

    /**
     * 显式镜像档「以档案为准」。
     *
     * 只对**档案里声明过的范围**按档案对齐：`stores/<store>.json` 在档案里出现 ⇒ 这个店删掉档案里没有的键；
     * 档案里没出现的店（含所有账号档）一个字不动；`files/` 也只对档案里出现过的族对齐。
     * 红线清单（[ArchiveRedLines]）永不删。
     */
    MIRROR,
}
