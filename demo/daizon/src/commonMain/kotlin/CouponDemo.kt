import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Coupon(val title: String, val remaining: Int, val expiry: String, val color: Color)

private val mockCoupons = listOf(
    Coupon("北区早餐券", 3, "2025-02-28 到期", Color(0xFFFF8A50)),
    Coupon("南区午餐券", 1, "2025-01-31 到期", Color(0xFF5B8DEF)),
    Coupon("东区晚餐券", 5, "2025-03-31 到期", Color(0xFF4CAF8A)),
    Coupon("西区加餐券", 2, "2025-02-14 到期", Color(0xFFAB47BC)),
)

@Composable
fun CouponDemo() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "加餐券")
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TabRow(
                tabs = listOf("全部", "即将过期", "已用完"),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(mockCoupons.size) { i ->
                    CouponCard(mockCoupons[i])
                }
            }
        }
    }
}

@Composable
private fun CouponCard(coupon: Coupon) {
    Card {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 左侧色条
            Box(
                Modifier.width(6.dp).fillMaxHeight()
                    .background(coupon.color)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(coupon.title, style = MiuixTheme.textStyles.headline1)
                    Text(coupon.expiry, style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        coupon.remaining.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = coupon.color,
                    )
                    Text("张", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }
        }
    }
}
