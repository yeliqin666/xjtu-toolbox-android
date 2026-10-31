package com.xjtu.toolbox.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

private object JiaoxiaoxiAvatarAsset

@Composable
actual fun JiaoxiaoxiAvatar(modifier: Modifier) {
    val image = remember {
        JiaoxiaoxiAvatarAsset::class.java.classLoader
            ?.getResourceAsStream("daizong_avatar.webp")
            ?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() }
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape),
        )
    } else {
        // 资源没打进包时不崩，给一块同色系的圆底兜底。
        Box(modifier.clip(CircleShape).background(Color(0xFFE7D8DC)))
    }
}
