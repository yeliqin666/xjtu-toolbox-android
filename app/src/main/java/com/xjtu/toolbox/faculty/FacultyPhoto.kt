package com.xjtu.toolbox.faculty

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

/**
 * 教师证件照加载。
 *
 * 项目里没有 Coil/Glide，为一个页面引入图片库不划算，所以这里用 OkHttp + BitmapFactory
 * 自己做一个够用的版本：内存 LRU + 采样压缩 + 首字母兜底。
 *
 * 证件照都是几十 KB 的小图，不做磁盘缓存——列表滚动期间内存缓存已经够，
 * 退出页面就该释放，不值得为它占用户存储。
 */
object FacultyPhotoLoader {

    /** 约 60 张 200px 证件照的量级，够一屏列表反复滚动 */
    private const val CACHE_SIZE_BYTES = 12 * 1024 * 1024

    /** 证件照原图偶有几 MB 的，按目标尺寸采样后再解码，避免列表滚动时 OOM */
    private const val TARGET_PX = 256

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 记录取过但失败的 URL，避免列表反复滚动时对同一张坏图重复发请求 */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun load(url: String): Bitmap? {
        if (url.isBlank() || url in failed) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                    .header("Referer", FacultyApi.FACULTY_HOST)
                    .get().build()
                val bytes = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null.also { failed.add(url) }
                    resp.body?.bytes()
                } ?: return@withContext null.also { failed.add(url) }

                // 先只读尺寸，再按需采样解码
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / sample > TARGET_PX * 2) sample *= 2

                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?.also { cache.put(url, it) }
                    ?: null.also { failed.add(url) }
            } catch (e: Exception) {
                failed.add(url)
                null
            }
        }
    }
}

/**
 * 教师头像。取不到照片时回退到姓名首字，与账号页的 AccountAvatar 视觉保持一致。
 */
@Composable
fun FacultyAvatar(
    member: FacultyMember,
    size: Int = 56,
    modifier: Modifier = Modifier,
) {
    val url = remember(member.picUrl) { FacultyApi.absoluteUrl(member.picUrl) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNotBlank()) bitmap = FacultyPhotoLoader.load(url)
    }

    val accent = MiuixTheme.colorScheme.primary
    val gradient = Brush.verticalGradient(
        listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.10f))
    )
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        val photo = bitmap
        when {
            photo != null -> Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            member.name.isNotBlank() -> Text(
                member.name.take(1),
                color = accent,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            else -> Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size((size * 0.46f).dp),
            )
        }
    }
}
