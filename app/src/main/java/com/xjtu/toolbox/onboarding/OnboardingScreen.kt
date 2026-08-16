package com.xjtu.toolbox.onboarding

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.xjtu.toolbox.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次启动三屏引导：功能介绍 / 隐私说明 / 登录引导。
 *
 * 设计：HorizontalPager + 底部圆点 + 单按钮。已登录用户依然看到前两屏，
 * 登录引导屏直接变"开始使用"。逻辑委托给 [onFinish]，由调用方写入
 * [OnboardingStore]。
 */
@Composable
fun OnboardingScreen(
    isLoggedIn: Boolean,
    onFinish: () -> Unit,
) {
    val pages = listOf(
        OnboardingPage(
            emoji = "📚",
            title = "功能一览",
            body = "课表、成绩、空教室、校园卡、通知、付款码……  \n把校园服务装进一个盒子。"
        ),
        OnboardingPage(
            emoji = "🔒",
            title = "隐私优先",
            body = "凭据与 Cookie 不参与备份（Auto Backup 自动排除）。  \n通知、余额等缓存是临时信息，重装即清。"
        ),
        OnboardingPage(
            emoji = "✨",
            title = if (isLoggedIn) "开始使用" else "登录后开始",
            body = if (isLoggedIn) "你已经登录好了，直接去首页。" else "需要登录后才能抓取课表、成绩、校园卡等数据。"
        ),
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { pageIdx ->
                val page = pages[pageIdx]
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        page.emoji,
                        fontSize = 80.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        page.title,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        page.body,
                        textAlign = TextAlign.Center,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // 圆点：第几页（共 pages.size 页）
            Row(
                Modifier
                    .padding(vertical = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "第 ${pagerState.currentPage + 1} 页，共 ${pages.size} 页"
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == pagerState.currentPage) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == pagerState.currentPage)
                                    MiuixTheme.colorScheme.primary
                                else
                                    MiuixTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            // 按钮：最后一页"开始使用"，中间页"下一步"
            Button(
                onClick = {
                    if (pagerState.currentPage < pages.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onFinish()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    if (pagerState.currentPage < pages.size - 1) "下一步"
                    else if (isLoggedIn) "进入岱宗盒子" else "去登录"
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class OnboardingPage(
    val emoji: String,
    val title: String,
    val body: String,
)