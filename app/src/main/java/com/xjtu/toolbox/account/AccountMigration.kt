package com.xjtu.toolbox.account

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.util.AppDatabase
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.util.PersistentCookieJar
import java.io.File

/**
 * 一次性把旧单账号数据迁入首个 [Account] 命名空间。
 *
 * 幂等：以 [AccountStore.migrationDone] 为闸门，仅执行一次。
 * 必须在 AccountManager 初始化、UI 恢复凭据之前同步完成。
 */
object AccountMigration {

    private const val TAG = "AccountMigration"

    /**
     * @return 迁移后（或已迁移）的激活账号；无旧账号且 AccountStore 为空时返回 null。
     */
    fun runIfNeeded(context: Context, accountStore: AccountStore, credentialStore: CredentialStore): Account? {
        if (accountStore.migrationDone) {
            // 已迁移过：仅恢复 activeAccountId 到 AccountContext
            accountStore.activeAccount()?.let { AccountContext.activeAccountId = it.accountId }
            return accountStore.activeAccount()
        }

        val appContext = context.applicationContext
        val oldCreds = credentialStore.load()
        if (oldCreds == null) {
            // 全新安装：无旧账号可迁
            accountStore.migrationDone = true
            return null
        }

        val (username, password) = oldCreds
        val accountId = username
        Log.i(TAG, "Migrating legacy single-account data to accountId=$accountId")

        val account = Account(
            accountId = accountId,
            password = password,
            accountType = credentialStore.accountType,
            nickname = credentialStore.loadNickname(),
            fpVisitorId = credentialStore.loadFpVisitorId(),
            rsaPublicKey = credentialStore.loadRsaPublicKey(),
            rsaKeyTime = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
        )
        accountStore.upsert(account, setActive = true)

        val suffix = "_" + accountId.replace(Regex("[^a-zA-Z0-9]"), "_")

        // 关键迁移步骤：任一失败则抛出，阻止 migrationDone 标记，下次启动可重试（步骤均幂等）。
        try {
            // 1) cookies 迁移：cookies_normal / cookies_webvpn → cookies_*_suffix
            runCatching {
                val oldNormal = PersistentCookieJar(appContext, "cookies_normal")
                val raw = oldNormal.exportRaw()
                if (raw.isNotBlank()) {
                    PersistentCookieJar(appContext, "cookies_normal$suffix").importRaw(raw)
                }
                oldNormal.clear()  // 清掉旧池避免混淆
            }.onFailure { Log.w(TAG, "cookies_normal migration failed", it) }

            runCatching {
                val oldWebvpn = PersistentCookieJar(appContext, "cookies_webvpn")
                val raw = oldWebvpn.exportRaw()
                if (raw.isNotBlank()) {
                    PersistentCookieJar(appContext, "cookies_webvpn$suffix").importRaw(raw)
                }
                oldWebvpn.clear()
            }.onFailure { Log.w(TAG, "cookies_webvpn migration failed", it) }

            // 2) DataCache 目录迁移：data_cache → data_cache_suffix
            migrateDir(File(appContext.cacheDir, "data_cache"), File(appContext.cacheDir, "data_cache$suffix"))

            // 3) Agent 会话目录迁移
            migrateDir(File(appContext.filesDir, "agent_sessions"), File(appContext.filesDir, "agent_sessions$suffix"))

            // 4) 交晓智会话目录迁移
            migrateDir(File(appContext.filesDir, "jiaoxiaozhi_sessions"), File(appContext.filesDir, "jiaoxiaozhi_sessions$suffix"))

            // 5) Room custom_courses.accountId 回填（关键：失败则旧自定义课程将永远查不到）
            try {
                AppDatabase.getInstance(appContext).openHelper.writableDatabase
                    .execSQL("UPDATE custom_courses SET accountId = ? WHERE accountId = ''", arrayOf(accountId))
            } catch (e: Exception) {
                Log.e(TAG, "room custom_courses backfill failed — aborting migration", e)
                throw e
            }

            // 6) 校园卡缓存 prefs 迁移：campus_card → campus_card_suffix
            runCatching {
                val old = appContext.getSharedPreferences("campus_card", Context.MODE_PRIVATE)
                val target = appContext.getSharedPreferences("campus_card$suffix", Context.MODE_PRIVATE)
                if (target.all.isEmpty()) {
                    target.edit().apply {
                        old.all.forEach { (k, v) -> putAny(k, v) }
                    }.apply()
                }
                old.edit().clear().apply()
            }.onFailure { Log.w(TAG, "campus_card prefs migration failed", it) }
        } catch (e: Exception) {
            // 关键步骤失败：不标记完成，下次启动重试（步骤幂等：rename 目标已存在则跳过，SQL WHERE accountId='' 可重复）
            Log.e(TAG, "Migration aborted (will retry next launch): ${e.message}", e)
            AccountContext.activeAccountId = accountId
            return accountStore.get(accountId)
        }

        AccountContext.activeAccountId = accountId
        accountStore.migrationDone = true
        Log.i(TAG, "Migration completed for accountId=$accountId")
        return account
    }

    /**
     * 把旧目录迁到目标目录：优先 renameTo（同文件系统原子），失败则逐文件 copy 后删源。
     * 目标已存在则跳过（幂等）。源不存在则 no-op。任一 IO 失败抛 [java.io.IOException]。
     */
    private fun migrateDir(old: File, target: File) {
        if (!old.exists()) return
        if (!old.isDirectory) return
        if (target.exists()) {
            // 目标已存在（上次迁移残留）：清空源以避免混淆，不再覆盖目标
            old.deleteRecursively()
            return
        }
        if (old.renameTo(target)) return
        // rename 失败（跨挂载点等）：fallback 逐项 copy + delete
        target.mkdirs()
        old.walkTopDown().forEach { src ->
            val rel = src.relativeTo(old).path
            val dst = File(target, rel)
            if (src.isDirectory) dst.mkdirs() else {
                src.copyTo(dst, overwrite = true)
            }
        }
        if (!old.deleteRecursively()) {
            Log.w(TAG, "migrateDir: source leftover after copy: $old")
        }
    }

    /** 把 SharedPreferences 任意类型值写入 Editor。 */
    private fun android.content.SharedPreferences.Editor.putAny(k: String, v: Any?): android.content.SharedPreferences.Editor {
        return when (v) {
            is String -> putString(k, v)
            is Int -> putInt(k, v)
            is Long -> putLong(k, v)
            is Float -> putFloat(k, v)
            is Boolean -> putBoolean(k, v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, v as Set<String>)
            else -> this
        }
    }
}
