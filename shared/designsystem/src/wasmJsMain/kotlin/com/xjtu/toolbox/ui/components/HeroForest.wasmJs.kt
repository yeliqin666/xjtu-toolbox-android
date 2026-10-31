package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import xjtutoolbox.shared.designsystem.generated.resources.Res
import xjtutoolbox.shared.designsystem.generated.resources.hero_forest

@Composable
actual fun HeroForestBackground(modifier: Modifier) {
    Image(
        painter = painterResource(Res.drawable.hero_forest),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}
