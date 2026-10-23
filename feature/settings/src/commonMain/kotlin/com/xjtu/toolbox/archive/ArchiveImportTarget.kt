package com.xjtu.toolbox.archive

/** 导入侧的端内写入缝（§1.4 缝 A 的写半边）。 */
interface ArchiveImportTarget {

    /** 本端某个端内 store 的现状（含旁挂时间戳）；端上没有它 ⇒ null。 */
    fun readStore(rawName: String): StoreSnapshot?

    /** 把一个键写进端内 store（[rawName] = 端内名，含后缀）。 */
    fun putKey(rawName: String, key: String, value: StoreValue, at: Long?)

    /** 本端 `files/` 里这个相对路径的现状；不存在 ⇒ null。 */
    fun readFile(suffix: String, relPath: String): String?

    /** 写一个 `files/` 条目（相对路径 = `files/` 之下的路径，目录名不带 suffix，§2.3）。 */
    fun putFile(suffix: String, relPath: String, content: String)

    /**
     * **键名归一**（§6.3 逐族迁移）：档案里这条 `files/` 相对路径在本端要落成的端内相对路径；默认原样。
     *
     * [accountId] = `accounts/<suffix>` 那一档在 `manifest.accounts` 里的账号（`global/` 下 ⇒ null）：
     * 端内名要学号的那一族（[ArchiveScoreReport]）靠它把学号拼回去，而学号**不进档案路径**。
     *
     * 返回 null = 本端拼不出这个端内名（档案里没那一档账号、本端也没登录）⇒ 这一条跳过并计入
     * [ImportResult.Ok.filesSkipped]，整次导入照常跑完（§8.6：绝不抛）。
     */
    fun fileRelPath(suffix: String, accountId: String?, relPath: String): String? = relPath

    /**
     * **形状归一**（§6.3 逐族迁移）：档案里的字节 → 本端要写的字节；默认原样。null = 转不动 ⇒ 跳过并计数。
     *
     * [relPath] 是**档案里**那条路径（族按它认出来），[content] 是档案里那份字节；
     * 落地路径用 [fileRelPath] 算出来的那个。
     */
    fun fileContent(suffix: String, accountId: String?, relPath: String, content: String): String? = content
    /**
     * **删键**（镜像档「以档案为准」要用，§6.4）：端上不支持 ⇒ `false`，这一笔当「本端保留」。
     *
     * 红线清单上的店不会走到这里（[ArchiveRedLines] 在共享层先挡掉了）。
     */
    fun removeKey(rawName: String, key: String): Boolean = false

    /**
     * 端内某个账号档 `files/` 之下 `family` 那一族**真有的**相对路径（与 [readFile] 的入参同一套写法）。
     *
     * 镜像档只用它枚举「档案里出现过的族」在本端多出来的条目；默认空 = 这一端不可枚举 ⇒ 这一族一个都不删。
     */
    fun listFiles(suffix: String, family: String): List<String> = emptyList()

    /** 删掉端内一条 `files/`；默认 `false` = 这一端不删（保守口径：拿不准就留着）。 */
    fun removeFile(suffix: String, relPath: String): Boolean = false

}
