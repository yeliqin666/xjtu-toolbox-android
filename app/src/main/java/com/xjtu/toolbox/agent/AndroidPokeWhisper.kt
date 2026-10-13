package com.xjtu.toolbox.agent

import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.nav.AppRoute
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.xjtu.toolbox.platform.showBriefMessage
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.launch
import com.xjtu.toolbox.ui.adaptive.readableWidth
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 「戳一下底栏屁岱」的回应气泡 —— `:core` 的 `ChatPanel` 用 `pokeWhisper` 槽位把它插进来。
 *
 * 它认三件还在这一端的东西：提醒气泡的中转站（`ProactiveBubbleHost.heroPokes` / `heroLine`）、
 * 形象外观（`pidaiNavAppearance()`）、以及迷你屁岱（`BloubBotIcon`，节拍由 `PidaiBeat` 驱动）。
 * 桌面上没有底栏屁岱、没有形象设置、也没有提醒气泡 ⇒ 那边这一格是空的（默认实现什么都不画），
 * 因为**根本没有可戳的形象** —— 如实降级，不是画出来点了没反应。
 *
 * 搬迁口径：从 `AgentChat.kt` **整段搬来**，只改了函数名（`PokeWhisper` → [AndroidPokeWhisper]）。
 */
@Composable
internal fun AndroidPokeWhisper(modifier: Modifier = Modifier) {
    val host = ProactiveBubbleHost
    val seen = remember { intArrayOf(host.heroPokes) }
    var text by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(host.heroPokes) {
        if (host.heroPokes == seen[0]) return@LaunchedEffect
        seen[0] = host.heroPokes
        text = host.heroLine ?: return@LaunchedEffect
        visible = true
        kotlinx.coroutines.delay(3500)
        visible = false
    }
    val look = pidaiNavAppearance()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible && text != null,
        enter = androidx.compose.animation.fadeIn(tween(160)) +
            androidx.compose.animation.scaleIn(
                androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f),
                initialScale = 0.7f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
            ),
        exit = androidx.compose.animation.fadeOut(tween(220)) +
            androidx.compose.animation.slideOutVertically(tween(220)) { it / 3 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.25f), spotColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .clickable { visible = false }
                .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BloubBotIcon(
                beat = PidaiBeat.IDLE,
                ink = look.ink,
                paper = MiuixTheme.colorScheme.surfaceVariant,
                cast = look.cast,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text.orEmpty(),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
    }
}
