package com.xjtu.toolbox.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

private object HeroForestAsset

@Composable
actual fun HeroForestBackground(modifier: Modifier) {
    val image = remember {
        HeroForestAsset::class.java.classLoader
            ?.getResourceAsStream("hero_forest.webp")
            ?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() }
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        // 资源没打进包时不崩，给一块同色系的斜向渐变兜底。
        Box(
            modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF21402E), Color(0xFF6E8F5A), Color(0xFFA7C4D8))),
            ),
        )
    }
}
