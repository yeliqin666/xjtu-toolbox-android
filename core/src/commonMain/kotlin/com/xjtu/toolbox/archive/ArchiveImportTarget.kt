package com.xjtu.toolbox.archive

/**
 * 导入侧的**端内写入缝**（§1.4 缝 A 的写半边，S1b）。
 *
 * 与只读的 [ArchiveStoreBackend] 并列：那边只枚举/读端内 store，这边的两个 `put` 才是导入真正落地的
 * 那两笔。刻意**逐键**而不是「写整个 store」：§6.4 的合并是键级裁决（本端胜的键一个都不能碰），
 * 而端内的写单位也是键（Android 是 `SharedPreferences.Editor`、桌面/web 是键值缝的 `put*`）。
 *
 * 三条由实现负责、`import` 不管的事：
 *  1. **按声明类型落地**（§8.6）：[StoreValue] 的标签就是端内该写哪种类型；同一个键在端内是另一种
 *     类型时按端内的读法容错（`getString`/`toIntOrNull`/`toBooleanStrictOrNull`），**绝不抛**；
 *  2. **旁挂时间戳**：[ArchiveImportTarget.putKey] 的 `at` 非空时，桌面/web 要按 §5.2 第 5 条重建
 *     `<key>#written_at`（否则导进来的那一份下次就被判成「没有年龄」）；
 *  3. **原子写**（§8.8）：`files/` 那一层先写临时文件再换名 —— 那是端内实现的事，档案层只给字节。
 *
 * `import` 的调用顺序保证「校验与合并在写之前全做完」⇒ 坏档案跑到这里之前就已经返回失败，
 * 端内一笔都没写（A7）。
 */
interface ArchiveImportTarget {

    /** 本端某个端内 store 的现状（含旁挂时间戳）；端上没有它 ⇒ null。 */
    fun readStore(rawName: String): StoreSnapshot?

    /**
     * 把一个键写进端内 store（[rawName] = 端内名逐字，含后缀）。
     *
     * @param at 档案里那个键的 `at`（§2.2）；null = 档案里没有它的年龄信息，端内不重建旁挂键。
     */
    fun putKey(rawName: String, key: String, value: StoreValue, at: Long?)

    /** 本端 `files/` 里这个相对路径的现状；不存在 ⇒ null。 */
    fun readFile(suffix: String, relPath: String): String?

    /** 写一个 `files/` 条目（相对路径 = `files/` 之下的路径，目录名不带 suffix，§2.3）。 */
    fun putFile(suffix: String, relPath: String, content: String)
}
