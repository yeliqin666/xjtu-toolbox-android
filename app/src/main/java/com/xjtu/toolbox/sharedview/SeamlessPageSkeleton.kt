package com.xjtu.toolbox.sharedview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.SkeletonBlock
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一镜到底宿主在「站点还没连上」时的首帧。
 *
 * KIT 要求目标 Activity 首帧**不透明全屏**，所以这块先铺满主题底色再画占位：
 * 卡片/列表的骨架形状贴近真实页面，转场落定后内容再顶上来，版面不会从空白跳一下。
 * 左上角是真返回键：登录可能几十秒，用户随时能退，退的时候正好接上 ROM 的缩回动画。
 */
@Composable
internal fun SeamlessPageSkeleton(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                SkeletonBlock(Modifier.width(132.dp).height(20.dp), cornerRadius = 10.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(Modifier.fillMaxWidth().height(104.dp), cornerRadius = 22.dp)
            Spacer(Modifier.height(12.dp))
            repeat(4) {
                SkeletonBlock(Modifier.fillMaxWidth().height(64.dp), cornerRadius = 18.dp)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 准备会话失败：把失败原因留在页上，重试/返回都不丢当前的 KIT 转场语义（返回直接 finish）。 */
@Composable
internal fun SeamlessPageFailure(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ErrorState(message, onRetry = onRetry)
            TextButton(text = "返回", onClick = onBack)
        }
    }
}
