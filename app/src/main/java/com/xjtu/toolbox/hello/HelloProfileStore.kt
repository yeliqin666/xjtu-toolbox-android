package com.xjtu.toolbox.hello

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.util.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 个人档案的取数与缓存。
 *
 * 策略：**缓存优先，网络兜后**。
 * - 首次登录抓一次，落盘（[DataCache]，按账号隔离）；
 * - 之后进入"我的"页直接读缓存，0 等待；
 * - 缓存超过 [REFRESH_AFTER_MS] 才在后台静默刷新一次，失败静默吞掉，不打扰用户。
 *
 * 档案是学籍数据，一学期都不会变，没有任何理由让用户每次进页面都等一次网络往返。
 */
object HelloProfileStore {

    private const val TAG = "HelloProfile"
    private const val CACHE_KEY = "hello_profile"

    /** 档案缓存有效期：30 天。学籍信息在学期内基本不变。 */
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L

    /** 超过 12 小时才值得后台再拉一次。 */
    private const val REFRESH_AFTER_MS = 12L * 60 * 60 * 1000L

    /** 防止"我的"页反复进出触发并发抓取。 */
    private val fetchLock = Mutex()

    private val gson = com.google.gson.Gson()

    /** DataCache 只存字符串，这里自己做 JSON 序列化；解析失败当作无缓存。 */
    private fun DataCache.readProfile(): HelloProfile? =
        get(CACHE_KEY, CACHE_TTL_MS)?.let {
            runCatching { gson.fromJson(it, HelloProfile::class.java) }.getOrNull()
        }

    private fun DataCache.writeProfile(profile: HelloProfile) =
        put(CACHE_KEY, gson.toJson(profile))

    fun cached(context: Context): HelloProfile? = DataCache(context).readProfile()

    /**
     * 确保有档案可用。
     *
     * @param force 忽略新鲜度，强制走网络（用户手动下拉刷新时用）。
     * @return 最新可用档案；网络失败且无缓存时返回 null。
     */
    suspend fun ensure(
        context: Context,
        manager: SessionManager?,
        force: Boolean = false,
    ): HelloProfile? {
        val cache = DataCache(context)
        val local = cache.readProfile()
        if (!force && local != null &&
            System.currentTimeMillis() - local.fetchedAt < REFRESH_AFTER_MS
        ) {
            return local
        }
        if (manager == null) return local

        return fetchLock.withLock {
            // 等锁期间别人可能刚拉完
            val fresh = cache.readProfile()
            if (!force && fresh != null &&
                System.currentTimeMillis() - fresh.fetchedAt < REFRESH_AFTER_MS
            ) {
                return@withLock fresh
            }
            try {
                val profile = withContext(Dispatchers.IO) {
                    val site = manager.ensureSite("hello")
                    HelloApi(site).getProfile()
                }
                cache.writeProfile(profile)
                withContext(Dispatchers.IO) { downloadAvatar(context, manager, profile) }
                profile
            } catch (e: Exception) {
                // 静默失败：这是锦上添花的信息源，拿不到就继续用缓存/退回原有 YWTB 信息，
                // 不该让"我的"页因此报错。
                Log.w(TAG, "fetch profile failed: ${e.message}")
                local
            }
        }
    }

    // ── 头像 ────────────────────────────────────────────────

    /**
     * 头像文件路径（按账号隔离）。头像是二进制，不适合塞进 JSON 缓存，单独落盘。
     */
    private fun avatarFile(context: Context): File =
        File(context.cacheDir, "avatar${AccountContext.safeSuffix()}.jpg")

    /**
     * 按账号 id 定位头像文件。账号管理页要同时显示**其他**账号的头像，
     * 而 [AccountContext.safeSuffix] 只反映当前激活账号，所以这里按同一规则自行拼后缀。
     * 规则必须与 [AccountContext.safeSuffix] 保持一致，改一处要改两处。
     */
    private fun avatarFileFor(context: Context, accountId: String?): File {
        val suffix = accountId
            ?.takeIf { it.isNotBlank() }
            ?.let { "_" + it.replace(Regex("[^a-zA-Z0-9]"), "_") }
            ?: "default"
        return File(context.cacheDir, "avatar$suffix.jpg")
    }

    /** 自定义头像优先于学工证件照；都没有则返回 null，调用方退回首字母。 */
    fun cachedAvatar(context: Context): Bitmap? =
        decode(customAvatarFile(context)) ?: decode(avatarFile(context))

    /** 指定账号的头像；没有缓存返回 null（调用方退回首字母）。 */
    fun cachedAvatarFor(context: Context, accountId: String?): Bitmap? =
        decode(customAvatarFileFor(context, accountId)) ?: decode(avatarFileFor(context, accountId))

    private fun decode(f: File): Bitmap? {
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /**
     * 下载头像并落盘。已存在且档案未更新过就跳过——头像 URL 里带内容哈希，
     * URL 没变就说明照片没换。
     */
    private fun downloadAvatar(context: Context, manager: SessionManager, profile: HelloProfile) {
        val url = profile.pictureUrl
        if (url.isBlank()) return
        val marker = File(context.cacheDir, "avatar${AccountContext.safeSuffix()}.url")
        val previous = runCatching { marker.readText() }.getOrNull()
        val target = avatarFile(context)
        if (previous == url && target.exists() && target.length() > 0) return

        try {
            val site = manager.getSiteOrNull("hello") ?: return
            val resp = site.client.newCall(Request.Builder().url(url).get().build()).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "avatar download HTTP ${it.code}")
                    return
                }
                val bytes = it.body?.bytes() ?: return
                if (bytes.isEmpty()) return
                // 先写临时文件再改名，避免下载中途被读到半张图
                val tmp = File(target.absolutePath + ".tmp")
                tmp.writeBytes(bytes)
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                marker.writeText(url)
                Log.d(TAG, "avatar cached: ${bytes.size} bytes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "avatar download failed: ${e.message}")
        }
    }

    // ── 自定义头像 ────────────────────────

    /** 头像最长边上限。界面上最大也就 72dp，512 结结实实够用，再大只是白占内存和磁盘。 */
    private const val CUSTOM_AVATAR_MAX_PX = 512

    /**
     * 用户自选的头像。放 [Context.getFilesDir] 而不是 cacheDir——用户特意设的东西，
     * 不该在系统清缓存时被默默抹掉。后缀规则与 [avatarFileFor] 一致。
     */
    private fun customAvatarFileFor(context: Context, accountId: String?): File {
        val suffix = accountId
            ?.takeIf { it.isNotBlank() }
            ?.let { "_" + it.replace(Regex("[^a-zA-Z0-9]"), "_") }
            ?: "default"
        return File(context.filesDir, "avatar_custom$suffix.jpg")
    }

    private fun customAvatarFile(context: Context): File =
        File(context.filesDir, "avatar_custom${AccountContext.safeSuffix()}.jpg")

    /** 当前账号是否已设自定义头像（决定要不要展示「恢复默认」）。 */
    fun hasCustomAvatar(context: Context): Boolean =
        customAvatarFile(context).let { it.exists() && it.length() > 0 }

    /**
     * 保存用户选的图片为头像。
     *
     * 用 [ImageDecoder] 而不是 [BitmapFactory]：前者会自动应用 EXIF 旋转，
     * 否则相机直出的照片会歪着显示。解码时就降采样，不把原图全尺寸读进内存。
     *
     * @return 成功与否；失败已记日志，调用方只需提示用户。
     */
    suspend fun saveCustomAvatar(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // 硬件 Bitmap 不能 compress，必须要软件分配
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > CUSTOM_AVATAR_MAX_PX) {
                        decoder.setTargetSampleSize(
                            Integer.highestOneBit(longest / CUSTOM_AVATAR_MAX_PX).coerceAtLeast(1)
                        )
                    }
                }
                val target = customAvatarFile(context)
                // 先写临时文件再改名，与证件照一致，避免写到一半被读
                val tmp = File(target.absolutePath + ".tmp")
                tmp.outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "compress failed" }
                }
                bitmap.recycle()
                if (target.exists()) target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
                true
            }.getOrElse {
                Log.w(TAG, "save custom avatar failed: ${it.message}")
                runCatching { File(customAvatarFile(context).absolutePath + ".tmp").delete() }
                false
            }
        }

    /** 恢复默认：删掉自定义头像，自然回退到学工证件照。 */
    fun clearCustomAvatar(context: Context) {
        runCatching { customAvatarFile(context).delete() }
    }

    /** 切换/注销账号时清掉，避免上一个账号的头像与档案泄露给下一个人。 */
    fun clear(context: Context) {
        runCatching { DataCache(context).invalidate(CACHE_KEY) }
        runCatching { avatarFile(context).delete() }
        runCatching { File(context.cacheDir, "avatar${AccountContext.safeSuffix()}.url").delete() }
        // 自定义头像不清：同一台设备上按账号隔离已经足够，抹掉只会让用户白设一次。
    }
}
