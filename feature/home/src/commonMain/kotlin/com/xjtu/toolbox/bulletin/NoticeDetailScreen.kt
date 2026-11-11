package com.xjtu.toolbox.bulletin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 站内公告详情。首页公告条「查看详情」的目标页：
 * - ROM 支持金标联盟一镜到底时，本屏由 [com.xjtu.toolbox.sharedview.NoticeDetailActivity] 承载（独立 Activity）；
 * - 其它设备压 [AppRoute.NoticeDetail] 路由，由首页公告卡经 Compose 转场放大进来。
 */
@Composable
fun NoticeDetailScreen(
    notice: AppRoute.NoticeDetail,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val glass = rememberPageGlass()
    val level = remember(notice.level) { BulletinLevel.parse(notice.level) }
    val accent = bulletinAccent(level) ?: MiuixTheme.colorScheme.primary

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "公告详情",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(padding.glassTop(glass)))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        bulletinLevelLabel(level),
                        style = MiuixTheme.textStyles.footnote2,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    notice.title,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            notice.body.ifBlank { "这条公告没有更多内容。" },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (notice.url.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        text = "在浏览器中打开",
                        onClick = { onOpenUrl(notice.url) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
