import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Transaction(val place: String, val time: String, val amount: String, val isIncome: Boolean)

private val mockTransactions = listOf(
    Transaction("北区二食堂", "今天 12:31", "-¥12.50", false),
    Transaction("东区超市", "今天 09:15", "-¥6.80", false),
    Transaction("充值", "昨天 18:00", "+¥100.00", true),
    Transaction("南区一食堂", "昨天 12:20", "-¥9.00", false),
    Transaction("图书馆打印", "昨天 10:05", "-¥1.20", false),
    Transaction("北区三食堂", "01/06  18:30", "-¥11.50", false),
    Transaction("东区洗衣", "01/06  14:00", "-¥2.00", false),
)

@Composable
fun CampusCardDemo() {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "校园卡",
                largeTitle = "校园卡",
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BalanceCard() }
            item {
                Text(
                    "近期消费",
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(mockTransactions.size) { i ->
                val tx = mockTransactions[i]
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tx.place, style = MiuixTheme.textStyles.headline1)
                            Text(tx.time, style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                        Text(
                            tx.amount,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (tx.isIncome) Color(0xFF4CAF8A) else MiuixTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(
                brush = Brush.linearGradient(listOf(Color(0xFF005BAC), Color(0xFF338FD9))),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text("西安交通大学校园卡", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text("张同学", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("余额", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Text("¥ 156.50", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

