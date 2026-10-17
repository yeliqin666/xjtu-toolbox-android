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
    }

    loadedImage?.takeIf { showImageViewer }?.let {
        SchoolCalendarImageViewer(loaded = it, onDismiss = { showImageViewer = false })
    }
    loadedImage?.let {
        SchoolCalendarImageCard(loaded = it, onOpen = { showImageViewer = true })
    }
}
