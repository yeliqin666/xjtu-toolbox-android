package com.xjtu.toolbox.archive

/**
 * 端内 store 投影（§1.4 缝 A，S2）。
 *
 * 与 `:core` 的 [com.xjtu.toolbox.platform.KeyValueStore] **并列、不复用为投影口**，理由三条：
 *  1. 它是「按名字开句柄」，**无法枚举**端上存在哪些 store —— A1 判据需要枚举；
 *  2. 它只有 String/Boolean/Int/Set 四档，**没有 Long/Float** —— §2.2 要求 `l`/`f` 带标签往返
 *     （Android 实测 `last_auto_update_check_at` 是 long、`card_today_*` 是 float）；
 *  3. 它的 `clear()` 是端内行为，粒度与档案的逐键合并不同。
 *
 * 本接口**只读**（S2）：`storeNames()` 枚举、`readStore()` 带类型读出。写入侧（`writeStore` /
 * `removeKeys`）与导入/导出入口、迁移、UI 分属 S4–S6，不在这一版。
 *
 * 三个 actual 复用的底层事实（不新建端内格式，C1 红线）：
 *  - Android = `getSharedPreferences(name, MODE_PRIVATE)` 的 `prefs.all`（带类型的 `Map<String, *>`）；
 *  - jvm    = `<数据根>/<name>.properties`（与 `:data` 的 `SecureStore.jvm.kt` 同一根、同一套 Properties 形状）；
 *  - web    = `localStorage` 的 `xjtu-toolbox.` 前缀（照 `KeyValueStore.wasmJs.kt`）。
 */
interface ArchiveStoreBackend {
    /** 端上真实存在的 store 名（原样，含后缀，如 `schedule_cache_2253415556`）。 */
    fun storeNames(): List<String>

    /**
     * 读一个 store 的全部键；不存在 ⇒ null。类型必须带出来（§2.2 `s/b/i/l/f/set`），
     * 桌面/web 的 `<key>#written_at` 由 [ArchiveCodec.normalizeWrittenAt] 折进 `writtenAt`（A10）。
     */
    fun readStore(rawName: String): StoreSnapshot?

    /**
     * 端上存在的 store 里，registry 标了「不进档案」的（§2.4）。默认空 —— 真正按 registry
     * 的排除在导出门面（S4–S6）里用 [ArchiveRegistry.isExcluded] 做，那一步才知道账号后缀清单。
     */
    fun excludedNames(): List<String> = emptyList()
}

/** 本端的 store 投影（三个 actual）。 */
expect fun archiveStoreBackend(): ArchiveStoreBackend

/**
 * 本端「数据根目录」的**绝对路径**（§1.2 / A13 前半）。
 *
 *  - Android = `context.filesDir`（与 `:data` 的 `dataFilesDir()` 同值）；
 *  - jvm    = `~/.local/share/xjtu-toolbox`（与 `:data` 的 `dataRoot()` 同一个 XDG 字面量）；
 *  - web    = `null`（浏览器无文件系统，`files` 那一层不存在）。
 *
 * ⚠️ 返回 [String] 而非 `java.io.File`：`:core` 的 commonMain 是**真多平台**（含 wasmJs），
 * 那里没有 `java.io.File`（这是 plan §1.3 草案里 `File?` 这一格在落地时的必要调整；`File` 只有
 * `:data` 那类 jvm+android 两个目标的 commonMain 才能用）。等值断言用 `.absolutePath` 对齐。
 */
expect fun archiveDataRoot(): String?
