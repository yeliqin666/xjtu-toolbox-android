package com.xjtu.toolbox.fitness

import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FitnessScreen(
    site: SiteSession,
    onBack: () -> Unit,
) {
    val api = remember(site) { FitnessApi(site) }
    val loginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    var years by remember { mutableStateOf<List<FitnessYear>>(emptyList()) }
    var selectedYear by remember { mutableStateOf<FitnessYear?>(null) }
    var score by remember { mutableStateOf<FitnessScore?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadYears() {
        loading = true
        error = null
        try {
            years = withContext(Dispatchers.IO) { api.getYears() }
            selectedYear = years.firstOrNull { it.checked } ?: years.firstOrNull()
            score = selectedYear?.let { year ->
                runCatching { withContext(Dispatchers.IO) { api.getScore(year.yearNum) } }
                    .onFailure { error = it.message ?: "该学年暂无体测数据" }
                    .getOrNull()
            }
        } catch (e: Exception) {
            if (e is AuthExpiredException) {
                loginState.markStaleAndRetry(LoginType.FITNESS, Routes.FITNESS)
                onBack()
                return
            }
            error = e.message ?: "体测查询失败"
        } finally {
            loading = false
        }
    }

    suspend fun selectYear(year: FitnessYear) {
        selectedYear = year
        loading = true
        error = null
        try {
            score = withContext(Dispatchers.IO) { api.getScore(year.yearNum) }
        } catch (e: Exception) {
            if (e is AuthExpiredException) {
                loginState.markStaleAndRetry(LoginType.FITNESS, Routes.FITNESS)
                onBack()
                return
            }
            score = null
            error = e.message ?: "体测查询失败"
        } finally {
            loading = false
        }
    }

    suspend fun refreshCurrent() {
        selectedYear?.let { selectYear(it) } ?: loadYears()
    }

    LaunchedEffect(site) { loadYears() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "体测查询",
                largeTitle = "体测查询",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refreshCurrent() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading && score == null -> LoadingState(
                "正在读取体测成绩…",
                Modifier.padding(padding)
            )
            years.isEmpty() && error != null && score == null -> ErrorState(
                "查询失败：$error",
                onRetry = { scope.launch { loadYears() } },
                modifier = Modifier.padding(padding)
            )
            years.isEmpty() -> EmptyState(
                "暂无可查询的体测学年",
                modifier = Modifier.padding(padding)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        years.forEach { year ->
                            YearChip(
                                year = year,
                                selected = year == selectedYear,
                                onClick = { scope.launch { selectYear(year) } }
                            )
                        }
                    }
                }
                score?.let { result ->
                    item { ScoreHero(result) }
                    item {
                        Text(
                            "项目成绩",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                        )
                    }
                    items(result.items) { FitnessItemCard(it) }
                }
                if (error != null && score == null) {
                    item {
                        ErrorState(
                            "查询失败：$error",
                            onRetry = { scope.launch { refreshCurrent() } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YearChip(year: FitnessYear, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.surfaceVariant
    val fg = if (selected) MiuixTheme.colorScheme.onPrimary
    else MiuixTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Text(year.name, color = fg, style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun ScoreHero(score: FitnessScore) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1565C0), Color(0xFF00897B))
                    )
                )
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DirectionsRun,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    score.studentName.ifBlank { "体测成绩" },
                    color = Color.White,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(score.sex, score.grade)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    score.reportStatus.ifBlank { score.reportType },
                    color = Color.White.copy(alpha = 0.88f),
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.width(86.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    score.totalScore,
                    color = Color.White,
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
                Text(
                    score.totalGrade,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FitnessItemCard(item: FitnessItem) {
    val accent = when (item.tone.lowercase()) {
        "green" -> Color(0xFF2E7D32)
        "red" -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(accent)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                item.name,
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    item.value,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    item.grade,
                    color = accent,
                    style = MiuixTheme.textStyles.footnote2
                )
            }
        }
    }
}
