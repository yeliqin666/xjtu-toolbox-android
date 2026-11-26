package com.xjtu.toolbox.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.util.redactUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 校历屏的原图槽（平台能力）—— 粘到 `:core` 的 [SchoolCalendarScreen] 上的那一半。 */
@Composable
fun AppSchoolCalendarImage(selectedYear: String?) {
    val context = LocalContext.current
    val imageApi = remember { SchoolCalendarImageApi(context) }
    val ratioStore = remember { com.xjtu.toolbox.platform.keyValueStore("school_calendar_image") }
    val placeholderRatio = remember {
        ratioStore.getString(RATIO_KEY)?.toFloatOrNull()?.takeIf { it in 0.3f..5f } ?: DEFAULT_RATIO
    }

    var calendarImages by remember { mutableStateOf<List<SchoolCalendarImage>>(emptyList()) }
    var loadedImage by remember { mutableStateOf<LoadedCalendarImage?>(null) }
    var showImageViewer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        calendarImages = try {
            withContext(Dispatchers.IO) { imageApi.getImages() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SchoolCalendar", "校历图片列表获取失败", e)
            emptyList()
        }
    }

    // 显示所选学期那一学年的校历；教务处还没发这一学年的，就显示最新一张
    val wantedImage = remember(calendarImages, selectedYear) {
        calendarImages.firstOrNull { it.year == selectedYear } ?: calendarImages.firstOrNull()
    }
    var failed by remember { mutableStateOf<SchoolCalendarImage?>(null) }
    LaunchedEffect(wantedImage) {
        val image = wantedImage ?: return@LaunchedEffect
        if (loadedImage?.image == image) return@LaunchedEffect
        loadedImage = try {
            withContext(Dispatchers.IO) {
                val bytes = imageApi.getImageBytes(image)
                decodeCalendarBitmap(bytes)?.let { LoadedCalendarImage(image, bytes, it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SchoolCalendar", "校历图片下载失败: ${image.url.redactUrl()}", e)
            null
        }
        if (loadedImage == null) {
            failed = image
        } else {
            loadedImage?.bitmap?.let { ratioStore.putString(RATIO_KEY, (it.width.toFloat() / it.height).toString()) }
        }
    }

    loadedImage?.takeIf { showImageViewer }?.let {
        SchoolCalendarImageViewer(loaded = it, onDismiss = { showImageViewer = false })
    }
    // 列表一到就出卡片占位；下载失败才整张不出
    val image = wantedImage ?: return
    if (failed == image) return
    SchoolCalendarImageCard(
        image = image,
        loaded = loadedImage?.takeIf { it.image == image },
        placeholderRatio = placeholderRatio,
        onOpen = { showImageViewer = true },
    )
}

/** 上次见过的校历图宽高比，没见过就按原图常见的约 4600×2000。 */
private const val RATIO_KEY = "image_ratio"
private const val DEFAULT_RATIO = 2.3f
