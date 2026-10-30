package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import xjtutoolbox.shared.designsystem.generated.resources.Res
import xjtutoolbox.shared.designsystem.generated.resources.daizong_avatar

@Composable
actual fun JiaoxiaoxiAvatar(modifier: Modifier) {
    Image(
        painter = painterResource(Res.drawable.daizong_avatar),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape),
    )
}
