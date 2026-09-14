package com.xjtu.toolbox.perf

import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xjtu.toolbox.AppLoginStateViewModel
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.agent.bot.botShapeById
import com.xjtu.toolbox.agent.bot.eyeOffsetFor
import com.xjtu.toolbox.util.CredentialStore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动相关的一次性成本（主线程上的同步工作）。
 *
 * 这些是「启动时挡在首帧前」的开销，各自只发生一次，所以用 [Perf.measureFirstTouch]。
 * 每项必须在**独立进程**里跑（一次 `am instrument` 只测一个类），否则先跑的那项会把
 * 类加载/JIT 成本吃掉，后跑的看起来「免费」。
 *
 * 环境限制（务必写进报告）：模拟器的 Keystore 是软件实现，没有真机 TEE/StrongBox 的
 * 硬件派生成本，所以这里量到的加密存储首建时间**低于真机**。
 */
@RunWith(AndroidJUnit4::class)
class PerfStartupTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 形象贴合表的类加载成本。
     *
     * 必须先于任何 `BotEngine.sample()`：那会经 `decalageAtTime` 触发同一张表，
     * 之后这里就测不到「首次」了。
     */
    @Test
    fun decalages_firstTouch() {
        val triangle = botShapeById("triangle")!!.radii
        val ns = Perf.measureFirstTouch { eyeOffsetFor(triangle, "idle") }
        Perf.reportFirstTouch("bot eyeFit table build", ns)
    }

    /**
     * 首帧课表磁盘快照：`File.exists()` + `readText()` + Gson 一轮的成本。
     *
     * `ScheduleScreen` 在 `remember` 里同步跑 4–6 次这种读取，而默认 tab 恰好是「日程」，
     * 所以它落在冷启动首帧上。先量清楚再决定要不要为它做异步化重构——如果只是几毫秒，
     * 那重构一个复杂页面的风险就不划算。
     */
    @Test
    fun scheduleDiskSnapshot_cost() {
        val gson = Gson()
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        // 约 40KB：一学期 40 门课的量级
        val json = (1..40).joinToString(",", "[", "]") { i ->
            """{"courseName":"课程名称第${i}门","courseCode":"C$i","teacher":"教师$i",
                "location":"中3-230$i","weekBits":"${"1".repeat(20)}","startSection":${i % 6 + 1},
                "endSection":${i % 6 + 2},"dayOfWeek":${i % 7 + 1}}"""
        }
        val f = java.io.File(ctx.cacheDir, "perf_probe_schedule.json")
        f.writeText(json)
        Perf.sink = f.length()

        val stats = Perf.measureMedian(warmup = 20, iterations = 200, rounds = 5) {
            // 5 次读取：贴近期刊页冷启动时真实读的文件个数
            repeat(5) {
                if (f.exists()) gson.fromJson<List<Map<String, Any>>>(f.readText(), type)
            }
        }
        Perf.report("schedule disk 5 files ($json KB)".let { "schedule disk snapshot x5" }, stats)
        f.delete()
    }

    /** 加密 prefs 首建：Keystore 密钥派生，项目自注 50–200ms。 */
    @Test
    fun encryptedPrefs_create() {
        val ns = Perf.measureFirstTouch {
            EncryptedSharedPreferences.create(
                "perf_probe_secure",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        Perf.reportFirstTouch("EncryptedSharedPreferences.create", ns)

        // 同一文件的**第二次** create：这是关键数据。项目里 CredentialStore/AccountStore/
        // PersistentCookieJar 都被反复 new（十几处调用点），而每个实例的 prefs 各自
        // lazy 初始化，所以这段成本会被重复支付很多次。
        val key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val ns2 = Perf.measureFirstTouch {
            EncryptedSharedPreferences.create(
                "perf_probe_secure",
                key,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        Perf.reportFirstTouch("  same-file create (2nd)", ns2)

        val ns3 = Perf.measureFirstTouch {
            EncryptedSharedPreferences.create(
                "perf_probe_secure",
                key,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        Perf.reportFirstTouch("  same-file create (3rd)", ns3)
    }

    /** 已建实例上的读取（热路径），对照首建成本。 */
    @Test
    fun encryptedPrefs_readWarm() {
        EncryptedSharedPreferences.create(
            "perf_probe_secure2",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).edit().putString("k", "v").apply()
        val cred = CredentialStore(ctx)
        val ns = Perf.measureFirstTouch { cred.load() }
        Perf.reportFirstTouch("CredentialStore.load (warm)", ns)
    }

    /**
     * 账号 JSON 解析成本随账号数的增长。
     *
     * 直接测 `Gson.fromJson` 而不是 `AccountStore.list()`：模拟器上没有账号，
     * `list()` 会在空数据时提前返回，测不出真实用户在多次切号后的解析开销。
     */
    @Test
    fun accountJsonParse_scaling() {
        val gson = Gson()
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        for (n in listOf(1, 5, 20)) {
            val json = accountJson(n)
            val stats = Perf.measureMedian(warmup = 50, iterations = 300, rounds = 5) {
                gson.fromJson<List<Map<String, Any>>>(json, type)
            }
            Perf.report("gson accounts N=$n", stats)
        }
    }

    /**
     * 整体冷启动主线程成本：一次构造涵盖凭据存储、账号解析、迁移、身份恢复。
     * 这是阶段②第 3 块要搬走的东西的总量。
     */
    @Test
    fun appLoginStateViewModel_init() {
        val app = ctx.applicationContext as android.app.Application
        val ns = Perf.measureFirstTouch {
            runCatching { AppLoginStateViewModel(app) }
                .onFailure { Log.w(Perf.TAG, "ViewModel init failed", it) }
        }
        Perf.reportFirstTouch("AppLoginStateViewModel()", ns)

        // 二次构造（存储已预热）作对照，差值即被搬走的一次性成本
        val ns2 = Perf.measureFirstTouch {
            runCatching { AppLoginStateViewModel(app) }
                .onFailure { Log.w(Perf.TAG, "ViewModel init failed", it) }
        }
        Perf.reportFirstTouch("AppLoginStateViewModel() 2nd", ns2)
    }

    private fun accountJson(n: Int): String {
        val rsa = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ" + "d".repeat(300)
        return (1..n).joinToString(",", "[", "]") { i ->
            """
            {"accountId":"20230$i","password":"pw$i","accountType":"UNDERGRADUATE",
             "nickname":"同学$i","fpVisitorId":"${"a".repeat(32)}",
             "rsaPublicKey":"$rsa","rsaKeyTime":1700000000000,"lastUsedAt":170000000$i}
            """.trimIndent()
        }
    }
}
