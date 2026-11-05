package com.xjtu.toolbox.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.CodeMatrixView
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.LocalPageVisible
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.rememberCampusNow
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 网格是**正方格**：一格的高恒等于一格的宽（=「组宽 + 缝」÷ 列数），一组的高度因此是
 * `格数 × 一格边长 − 缝`。
 *
 * 这里曾经有两档单元高度——「留出文字空间」的 88dp 高长方形与「紧凑」的 46dp 方块，靠设置
 * 里一个开关切换。后来发现那等于同一页卡片有两套尺寸设计：开着像小组件，关掉就只剩图标，
 * 2×1 里连名称都塞不下。现在统一成正方格，1×1 永远是个放得下「图标 + 名称」的正方形，
 * 2×1 / 1×2 / 2×2 也都是正方形拼出来的，尺寸与内容的对应关系只有一套。
 */

/**
 * 组件之间、以及组件与组边界之间的标准缝隙。
 *
 * 这个缝隙是**渲染时**保证的：每个块都在自己的格矩形里四面各内缩一半，
 * 所以两块在格坐标上紧挨着，画出来也一定隔着一条 [HomeTileGap]，
 * 不会出现「拖到一起挤成一条线」（需求 §3.4）。
 */
internal val HomeTileGap = 8.dp

/** 组件位置/尺寸变化的弹簧：阻尼偏低一点，才有液态玻璃那种「弹一下再停住」。 */
internal val HomeTileSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)

/** 右下角缩放把手的热区边长（§15）：贴着圆弧把手的一小块，太大容易和移动抢指针。 */
internal val RESIZE_HANDLE_TOUCH = 30.dp

/** 左上角移除按钮的热区边长：视觉只有 18dp，热区放大到 32dp，点偏一点也点得到。 */
internal val REMOVE_TOUCH = 32.dp

/** 组标题行本身的高度：固定值，组的高度因此是数据的纯函数（组排序的让位几何靠它）。 */
internal val GROUP_HEADER_ROW = 34.dp

/** 组标题行 + 它与网格之间的间距：首页算几何表用的「标题区总高」。 */
internal val GROUP_HEADER_HEIGHT = 42.dp

/**
 * 一个卡片组：标题行 + 组件网格。
 *
 * 组本身**不再是一张白卡**（需求 §2.1：取消「常用功能」的特殊样式、所有组平级），
 * 所以这里只画一行标题和一片毛玻璃组件；标题为空串时整行都不画（§2.3）。
 *
 * **渲染的输入是实时布局**（拖拽中 = 首页每帧按快照重算的那份，见 [HomeScreen]）：
 * - 被拖的那一块不画真身，改画一个弹簧跟随数据位置的**落点占位框**（[DropSlot]）；
 *   跟手的是首页顶层那一只幽灵块（[HomeDragGhost]）。
 * - 其余块弹簧补位到各自的数据格。
 *
 * [budgetRows] 是拖动中这一组的**行数锁**（= 起手快照的行数）：有了它，组高在整场拖拽里
 * 恒定，于是「命中用的几何」和「画出来的几何」永远严格相等——这是落点不飘的物理保证
 * （详见 [HomeLayout.moveTile] 的 rowBudget 说明）。非拖动时为 null，组高跟随实时数据。
 *
 * 组排序（拖标题行左侧的把手）走的是另一套：被拖组跟手浮起（[reorderLift]）、其它组按
 * 「越过中线」纯函数让位（[reorderShift]），松手才落盘。那套几何也是纯函数、按快照算，
 * 与卡片拖拽共用同一个 [HomeBoardGeometry]，坐标一律内容坐标（见 [HomeDragSession]）。
 */
@Composable
internal fun HomeGroupSection(
    group: HomeGroup,
    faces: Map<String, HomeServiceRow>,
    editing: Boolean,
    liquid: Boolean,
    /** 液态玻璃是否带折射；关掉只少一层 `lens`，模糊 / 活力 / 高光 / 投影都不动。 */
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    /** 拖动中锁定的行数；null = 跟随实时数据。 */
    budgetRows: Float?,
    /** 手指当前把这一组当成落点（且不是拖拽源组）：画接收高亮（§19）。派生量，由首页算。 */
    receiving: Boolean,
    /** 被拖组的跟手位移（含松手后的归位补偿）；null = 这一组不在被拖。 */
    reorderLift: Float?,
    /** 组排序中这一组的让位目标（px）：被拖组抽走后它该往哪挪。 */
    reorderShift: Float,
    onOpen: (HomeServiceRow) -> Unit,
    onStartEdit: () -> Unit,
    /**
     * 付款码取数（校园卡 2×2 以上的大卡直接画二维码）。
     *
     * 这条是原「小组件」那套的能力，合并进卡片组后保留：只在用餐时段 + 页面可见时取，取不到就
     * 照常画大卡内容。默认返回 null = 这一端拿不到（桌面 / Web 就靠这个默认值什么都不画）。
     */
    paymentCode: suspend () -> String? = { null },
    /**
     * 卡片能不能糊（只有卡片吃「纯色 / 渐变不模糊」这条规则；按钮与悬浮条照旧模糊）。
     * 见 [HomeTileFaceBox] 的同名参数。
     */
    blurGlass: Boolean = true,
    /** 卡片圆角（主页设置里那条滑条），实际还会被「边长 × 12」夹住，见 [HomeTileFaceBox]。 */
    cardCorner: Dp = 22.dp,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val tiles = group.tiles
    val contentRows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)
    // 拖动中用锁定的行数，否则用实时行数；组高据此弹簧伸缩
    val rows = budgetRows ?: contentRows
    val animRows by animateFloatAsState(targetValue = rows, animationSpec = HomeTileSpring, label = "groupRows")

    // 组排序的让位：被拖组跟手（lift），其它组弹簧到让位目标；排序一结束立刻 snap 回 0——
    // 那一刻布局已按新顺序就位，偏移只有立刻归零，视觉位置才连续（§16）
    val reordering = session.mode == HomeDragMode.GROUP
    val shiftAnim = remember { Animatable(0f) }
    LaunchedEffect(reorderShift, reorderLift != null, reordering) {
        when {
            reorderLift != null -> shiftAnim.snapTo(0f)
            !reordering -> shiftAnim.snapTo(0f)
            else -> launch { shiftAnim.animateTo(reorderShift, HomeTileSpring) }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (reorderLift != null) 6f else 0f)
            .graphicsLayer {
                translationY = reorderLift ?: shiftAnim.value
                alpha = if (session.groupId == group.id) 0.94f else 1f
            },
    ) {
        if (editing || group.title.isNotBlank()) {
            GroupHeader(
                group = group,
                editing = editing,
                liquid = liquid,
                refraction = refraction,
                backdrop = backdrop,
                onEditGroup = onEditGroup,
                onDeleteGroup = onDeleteGroup,
            )
            Spacer(Modifier.height(GROUP_HEADER_HEIGHT - GROUP_HEADER_ROW))
        }

        // 一格是正方形，所以「一格边长」只能从**量到的宽度**里推：外面这层 BoxWithConstraints
        // 只负责量宽度，里面那个 Box 才按「行数 × 边长 − 缝」定高。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val colUnit = (constraints.maxWidth + gapPx) / HomeGridMath.COLUMNS
            // 正方格：一格的高 = 一格的宽（见文件头那段「网格是正方格」）
            val rowUnit = colUnit
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { (animRows * rowUnit - gapPx).toDp() }),
            ) {
                // 手指当前认定的目标组画一圈接收高亮（§19）：松手会落到哪，按下那一刻就看得见
                if (receiving) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(
                                1.5.dp,
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
                                RoundedCornerShape(14.dp),
                            ),
                    )
                }

                if (tiles.isEmpty() && editing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .squircleClip(18.dp)
                            .border(
                                1.dp,
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                RoundedCornerShape(18.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "这一组还是空的，从右侧悬浮条拖一个功能进来",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                val dragId = session.tileId
                // 拖动中与**结算中**都画占位框：松手后幽灵从手指位置飞向落点，落点这一块先留成
                // 占位框；幽灵飞到与它重合才 finish() 切回真实块——外观一样，切换无感。
                // 只判 draggingTile 的话，结算期它会立刻变成落点处的真实块，和还在飞的幽灵重影。
                val ghosted = session.draggingTile || session.settling
                tiles.forEach { tile ->
                    val face = faces[tile.routeId] ?: return@forEach
                    key(tile.id) {
                        if (ghosted && tile.id == dragId) {
                            // 被拖块：不画真身，画一个弹簧跟随数据位置的落点占位框。
                            // 真身由顶层的幽灵块画（跟手），松手时幽灵吸附到这里、占位框渐隐。
                            DropSlot(tile, colUnit, rowUnit, gapPx, settling = session.settling, cardCorner = cardCorner)
                        } else {
                            HomeTileItem(
                                tile = tile,
                                face = face,
                                editing = editing,
                                liquid = liquid,
                                refraction = refraction,
                                backdrop = backdrop,
                                blurGlass = blurGlass,
                                cardCorner = cardCorner,
                                colUnit = colUnit,
                                rowUnit = rowUnit,
                                gapPx = gapPx,
                                paymentCode = paymentCode,
                                onOpen = { onOpen(face) },
                                onStartEdit = onStartEdit,
                                onRemoveTile = onRemoveTile,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 组标题行：编辑态左侧把手（拖动上下排序，手势在 [homeDragLayer] 上）、**点组名**打开
 * 「分组设置」抽屉，右侧一个删除按钮。
 *
 * 把手只画记号、不挂手势：命中与拖动全在手势层按快照几何判定，这里不再回读把手位置
 * （旧实现登记 `handleTops` 再拿它合成手指 y，那正是坐标被污染的一处）。
 */
@Composable
private fun GroupHeader(
    group: HomeGroup,
    editing: Boolean,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val titleColor = CourseColors.parseHex(group.colorHex)
        ?.let { if (dark) lerp(it, Color.White, 0.25f) else it }
        ?: ink
    // 编辑态下点组名打开「分组设置」抽屉
    val titleTap = if (editing) {
        Modifier.pointerInput(group.id) { detectTapGestures(onTap = { onEditGroup() }) }
    } else {
        Modifier
    }

    // 高度固定（GROUP_HEADER_ROW）：组的总高因此是数据的纯函数，组排序的让位几何不依赖回读
    Row(
        Modifier.fillMaxWidth().height(GROUP_HEADER_ROW).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, contentDescription = "拖动排序", tint = muted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        if (group.title.isNotBlank()) {
            Text(
                group.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 必须给满 weight（不能 fill = false）：fill = false 时 Text 只量到自己的实际
                // 宽度，它那份**配给但没用掉**的空间不会还给后面的兄弟，于是右侧的删除按钮
                // 被顶到「标题 + 半个剩余宽度」那里，看着就是不居右。
                modifier = Modifier.weight(1f).then(titleTap),
            )
        } else if (editing) {
            Text(
                "未命名分组",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = muted.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f).then(titleTap),
            )
        } else {
            // 这一行只在「编辑态，或组名非空」时才会被画（见外层判断），这里是兜底：
            // 无非让一个空占位把右侧按钮顶到边上
            Spacer(Modifier.weight(1f))
        }
        if (editing) {
            GroupDeleteButton(liquid = liquid, refraction = refraction, backdrop = backdrop, onClick = onDeleteGroup)
        }
    }
}

/** 组标题行右侧的删除按钮：液态玻璃小圆钮（§2.4）。 */
@Composable
private fun GroupDeleteButton(liquid: Boolean, refraction: Boolean, backdrop: Backdrop?, onClick: () -> Unit) {
    val dark = LocalIsDarkTheme.current
    val fill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    // 按钮不跟卡片那条「纯色 / 渐变不模糊」的规则：它浮在内容上面，模糊的是此刻盖住的卡片，
    // 糊得动也值得糊。纯色 / 渐变下它照旧模糊 + 折射。
    val material = when {
        liquid && backdrop != null -> Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                padding = maxOf(padding, 8.dp.toPx())
                vibrancy()
                blur(6.dp.toPx())
                if (refraction) lens(6.dp.toPx(), 8.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.6f) },
            shadow = null,
            onDrawSurface = { drawRect(fill) },
        )
        // 这一帧没有采样源（不该发生）：退回半透明底，别画成一片透明
        liquid -> Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        else -> Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    }
    Box(
        Modifier
            .size(30.dp)
            .then(material)
            .pointerInput("deleteGroup") { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "删除这一组",
            tint = MiuixTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 校园卡大卡上的付款码。
 *
 * 只在「用餐时段 + 这一页可见」时去取码（[isMealPaymentTime]，见 `PaymentCodeTime.kt`），取到就
 * 整块盖成二维码并每 30 秒自动刷新；取不到 / 不在时段就什么都不画，底下的大卡内容照旧——所以
 * 绝大多数时候它只花一次「现在是不是饭点」的判断，不发任何请求。
 */
@Composable
private fun PaymentCodeOverlay(fetch: suspend () -> String?, radius: Dp) {
    val now = rememberCampusNow()
    val active = LocalPageVisible.current && isMealPaymentTime(now.time)
    val code by produceState<String?>(null, active) {
        value = null
        while (active) {
            value = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 没登录 / 校园卡没登 / 网络抖一下都走这条：不画码，也不弹错
                null
            }
            delay(PAYMENT_REFRESH_MS)
        }
    }
    val matrix = remember(code, active) { code?.takeIf { active }?.let { qrCodeModules(it) } } ?: return
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(radius))
            .background(Color.White)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            CodeMatrixView(matrix = matrix, quietZoneModules = 1, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "付款码 · 自动刷新",
            fontSize = 10.sp,
            color = Color.Black.copy(alpha = 0.65f),
            maxLines = 1,
        )
    }
}

/** 付款码自动刷新的间隔（与原「小组件」那套一致）。 */
private const val PAYMENT_REFRESH_MS = 30_000L

/**
 * 卡片的点击层：整块点开 + 长按进编辑态，并把「从这一块出发」的矩形交给一镜到底。
 *
 * 和经典屏 `opensRow` 是同一套接线：`expandOriginSource` 登记起点，按下先 `arm`（Compose 回落
 * 转场用它画放大），再按宿主有没有接 [HomeServiceRow.onSeamlessClick] 决定走 KIT 独立 Activity
 * （金标联盟 SharedViewKit）还是普通跳转。宿主没接就退回 `onOpen`，桌面 / Web 与老 ROM 的行为不变。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.tileOpen(
    face: HomeServiceRow,
    radius: Dp,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
): Modifier {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    return this
        .expandOriginSource(origin)
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {
                origin.arm(face.key, radius, density, null)
                val seamless = face.onSeamlessClick
                val bounds = origin.boundsInWindow()
                if (seamless != null && bounds != null) {
                    seamless(SeamlessCardSource(bounds, radius))
                } else {
                    onOpen()
                }
            },
            onLongClick = onStartEdit,
        )
}

/**
 * 落点占位框：被拖块在**实时布局**里的位置与尺寸，弹簧跟位。
 * 「松手会落在哪」在拖动全程都看得见；松手时幽灵块飞过来与它重合，预览固化成数据。
 *
 * [settling] 为真（松手结算中）时它**渐隐**：幽灵块正飞过来长成原大，落点会在这段时间里被
 * 盖住，所以淡出不会被看见；而等 `finish()` 那一刻才硬消失的话，边框会「啪」一下没掉。
 */
@Composable
private fun DropSlot(
    tile: HomeTile,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
    settling: Boolean,
    /** 占位框跟着卡片圆角走，松手前看到的「空位」才和真身一个形状。 */
    cardCorner: Dp = 22.dp,
) {
    val density = LocalDensity.current
    val x by animateFloatAsState(targetValue = tile.x, animationSpec = HomeTileSpring, label = "dropX")
    val y by animateFloatAsState(targetValue = tile.y, animationSpec = HomeTileSpring, label = "dropY")
    val w by animateFloatAsState(targetValue = tile.w, animationSpec = HomeTileSpring, label = "dropW")
    val h by animateFloatAsState(targetValue = tile.h, animationSpec = HomeTileSpring, label = "dropH")
    // 淡出比吸附飞行稍快一点收尾（飞行是弹簧，约 200–300ms）：这样淡完时幽灵已经盖住它了
    val fade by animateFloatAsState(
        targetValue = if (settling) 0f else 1f,
        animationSpec = tween(200),
        label = "dropSlotFade",
    )
    val frame = cellFrame(x, y, w, h, colUnit, rowUnit, gapPx)
    // 圆角跟卡片同一条设置（主页设置滑条）；「边长 × 12」是上限，小格才不会被削成圆饼
    val radius = cardCorner.coerceAtMost((minOf(w, h) * 12f).dp)
    val accent = MiuixTheme.colorScheme.primary
    Box(
        Modifier
            // 占位框必须压在同组其它块**之上**：它是"松手会落到这"的唯一提示，
            // 被邻居盖住就等于没有。层级只有 2，仍然在顶层幽灵块（20）之下。
            .zIndex(2f)
            .offset { frame.leftTop }
            .size(with(density) { frame.widthPx.toDp() }, with(density) { frame.heightPx.toDp() })
            .graphicsLayer { alpha = fade }
            // 幽灵是半透明盖在它上面的，所以底色给足、边框加粗，透过幽灵也能看清这块"空位"
            .background(accent.copy(alpha = 0.20f), RoundedCornerShape(radius))
            .border(2.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(radius)),
    )
}

/**
 * 「格 → 像素」的唯一换算入口：块的左上角与尺寸都从这里出。
 *
 * 之前 DropSlot 和 HomeTileItem 各写一遍 `x * colUnit` / `w * colUnit - gap`，两处一旦
 * 有一点不一致，占位框和真身就对不上（这也是「网格不对」最容易复发的地方）。收敛成
 * 纯函数后，两者永远同一个矩形。
 *
 * 注意：块贴在格的左上角、宽高各少一个 gap，所以视觉上缝隙集中在右/下侧——这是既有
 * 视觉，保持一致，不在这次重构里改动。
 */
private class CellFrame(val leftTop: IntOffset, val widthPx: Float, val heightPx: Float)

private fun cellFrame(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
): CellFrame = CellFrame(
    leftTop = IntOffset((x * colUnit).roundToInt(), (y * rowUnit).roundToInt()),
    widthPx = (w * colUnit - gapPx).coerceAtLeast(1f),
    heightPx = (h * rowUnit - gapPx).coerceAtLeast(1f),
)

/**
 * 一块组件。位置由格矩形算出，**位置变化用弹簧动画**（§6：挤开、重排都要 Q 弹）。
 *
 * 编辑态**不挂任何拖拽手势**——移动、缩放、组排序全部由顶层的 [homeDragLayer] 按快照
 * 几何命中并接管。这里只在两种情况挂手势：
 * - 非编辑态：整块点击打开、长按进编辑态（此时 [homeDragLayer] 根本没挂载，不冲突）；
 * - 编辑态的左上角移除按钮：一个独立的点击热区（手势层对这块放行，见 `HomeBoardGeometry.hit`）。
 *
 * 尺寸与位置一律走弹簧（缩放也一样）：缩放时数据是一格一格跳的，snap 会把这些跳变原样
 * 画出来、看着一顿一顿；走弹簧边缘才连续地挪过去。
 */
@Composable
private fun HomeTileItem(
    tile: HomeTile,
    face: HomeServiceRow,
    editing: Boolean,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
    /** 付款码取数；null 的语义见 [HomeGroupSection] 的同名参数。 */
    paymentCode: suspend () -> String? = { null },
    /** 这张卡能不能糊；见 [HomeTileFaceBox] 的同名参数。 */
    blurGlass: Boolean = true,
    /** 卡片圆角；见 [HomeGroupSection] 的同名参数。 */
    cardCorner: Dp = 22.dp,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
) {
    val density = LocalDensity.current

    // 位置：跟数据弹簧（拖动中被挤开的块平滑补位）
    val animX by animateFloatAsState(targetValue = tile.x, animationSpec = HomeTileSpring, label = "tileX")
    val animY by animateFloatAsState(targetValue = tile.y, animationSpec = HomeTileSpring, label = "tileY")

    // 尺寸：被缩的那一块也要跟着弹簧走（不再 snap）。尺寸是「数据格数」，拖动把手指位移换算成
    // 整数格，按下与松手之间会一格一格地跳；snap 会把这些跳变原样画出来，看着一顿一顿的。
    // 走弹簧后边缘是连续挪过去的，缩放过程才像「拉」而不是「跳格」。
    val animW by animateFloatAsState(targetValue = tile.w, animationSpec = HomeTileSpring, label = "tileW")
    val animH by animateFloatAsState(targetValue = tile.h, animationSpec = HomeTileSpring, label = "tileH")

    // 与 DropSlot 共用同一个换算入口：真身和占位框永远同一个矩形
    val frame = cellFrame(animX, animY, animW, animH, colUnit, rowUnit, gapPx)

    // 非编辑态：整块点击打开、长按进编辑态。编辑态这块不挂手势（交给手势层）。
    // 点击走「一镜到底」那套接线，和卡片圆角用同一个半径，转场时放大出来的形状才对得上。
    val radius = cardCorner.coerceAtMost((minOf(animW, animH) * 12f).dp)
    val tap = if (editing) {
        Modifier
    } else {
        Modifier.tileOpen(face = face, radius = radius, onOpen = onOpen, onStartEdit = onStartEdit)
    }

    Box(
        Modifier
            .offset { frame.leftTop }
            .size(with(density) { frame.widthPx.toDp() }, with(density) { frame.heightPx.toDp() })
            .then(tap),
    ) {
        HomeTileFaceBox(
            face = face,
            w = animW,
            h = animH,
            liquid = liquid,
            refraction = refraction,
            backdrop = backdrop,
            blurGlass = blurGlass,
            cardCorner = cardCorner,
            modifier = Modifier.fillMaxSize(),
        )

        // 校园卡的 2×2 大卡：用餐时段直接把付款码画在这块上（原「小组件」那套能力，
        // 合并进卡片组后保留）。取不到码 / 不在时段就什么都不画，下面的大卡内容照旧。
        if (!editing && animW >= 1.5f && animH >= 1.5f && face.key == AppRoute.CampusCard.id) {
            PaymentCodeOverlay(fetch = paymentCode, radius = radius)
        }

        if (editing) {
            // 左上角：移除这一块。热区 32dp，视觉只有 18dp，所以点偏一点也点得到。
            // 手势层对这个热区放行（HomeHit.Pass），所以这里的点击照常生效。
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(REMOVE_TOUCH)
                    .pointerInput(tile.id) { detectTapGestures(onTap = { onRemoveTile(tile.id) }) },
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除${face.title}",
                        tint = MiuixTheme.colorScheme.surface,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            // 右下角：缩放把手的**视觉**（§15）——把右下角那一小段圆弧染成主题色当把手。
            // 把手只画记号、不接手势：命中与拖动全在手势层。
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(RESIZE_HANDLE_TOUCH),
                contentAlignment = Alignment.Center,
            ) {
                val gripColor = MiuixTheme.colorScheme.primary
                Canvas(Modifier.size(20.dp)) {
                    // 以中心为圆心扫过 0°→90°（正右 → 正下），正好是右下角那段弧
                    drawArc(
                        color = gripColor,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * 组件的材质与内容：尺寸决定排版（§3.3），材质由「主页卡片液态玻璃」开关决定（§5.2）。
 *
 * 排版这一套是从「小组件模式」的首页（`dev` 的 `HomeWidgetGrid`）搬过来的逐档设计：
 * 1×1 是「图标 + 名称」，横条是「图标 + 小号名称 + 主要数据 + 一行说明」，竖条是
 * 「小号名称 + 大字数据 + 说明」，大方块再压一枚水印图标、字号也大一档。各档共用同一条
 * 「大字 / 一句话」判定（见 [FIGURE_MAX_CHARS] 与 [LIST_TILES]），所以同一个功能在任何
 * 尺寸下强调的都是同一件事。
 *
 * 与那一套唯一的差别：**1×1 一律画图标**，不拿状态数字顶替（小方块上认图标比读数字快）。
 * 网格统一是正方格，1×1 永远放得下图标与名称，所以这里没有任何「窄了就只剩图标」的降级档。
 *
 * 液态玻璃直接复用 App 里已有的那套 `drawBackdrop`（底栏、玻璃顶栏用的同一个），
 * 采样源是首页自己那一层背景，所以透出来的是壁纸而不是组件自己（会成环）。
 */
@Composable
internal fun HomeTileFaceBox(
    face: HomeServiceRow,
    w: Float,
    h: Float,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    /**
     * 这张卡**能不能糊**：有壁纸（自定义图片）为真。纯色 / 渐变背景上模糊这层没信息可采，
     * 只关掉它（`vibrancy` 光感、`lens` 折射、高光、轮廓、投影全都保留），底色换成
     * `flatGlassFill`（浅色更白 / 深色更深）。
     *
     * 只有**卡片**吃这条规则；按钮与编辑态悬浮条浮在内容上面，照旧模糊 + 折射。
     */
    blurGlass: Boolean = true,
    /**
     * 卡片圆角（dp，主页设置里那条滑条，默认 22）。
     *
     * 实际用的半径还会被「边长 × 12」夹住：4 个 22dp 的圆角扣在 1×1 上会变成圆饼，
     * 所以小格沿用原来的收敛范围（1×1 ≈ 12dp、2×2 用满 22dp）。
     */
    cardCorner: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val radius = cardCorner.coerceAtMost((minOf(w, h) * 12f).dp)
    val fill = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.62f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f)
    val iconColor = if (dark) lerp(face.color, Color.White, 0.4f) else face.color
    // 液态玻璃的填充要比毛玻璃更透：折射与高光是它的主角，填浓了就只剩一张白卡
    val liquidFill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    // 和底栏胶囊同一套环境光阴影（remember：shadow lambda 捕获的对象变了会触发重采样）
    val accent = MiuixTheme.colorScheme.primary
    val liquidShadow = remember(accent, dark) { floatingGlassShadow(accent, dark) }
    // 纯色 / 渐变背景上：**只关掉模糊**，活力 / 折射 / 高光 / 轮廓 / 投影全部保留，
    // 底色换成这一层「比页面更白（浅色）/ 更深（深色）」的薄色（与折射开关无关）
    val flatGlassFill = if (dark) Color.Black.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.52f)
    val surfaceFill = if (blurGlass) liquidFill else flatGlassFill

    val material = when {
        liquid && backdrop != null -> remember(backdrop, radius, surfaceFill, liquidShadow, refraction, blurGlass) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(radius) },
                effects = {
                    // 采样范围往外扩一圈，折射与模糊在轮廓边上也有真实内容可混、不变弱
                    // （和底栏胶囊、屁岱气泡同一手法）
                    padding = maxOf(padding, 12.dp.toPx())
                    vibrancy()
                    // 纯色 / 渐变上没有壁纸可糊：只省这一层，其余效果一个不少
                    if (blurGlass) blur(8.dp.toPx())
                    // 折射才是「液态」和「毛玻璃」的分界：边缘把背景扭出透镜的弯光，
                    // 带一点色散。带宽与位移用固定的物理宽度（和底栏一致），不随卡片
                    // 缩放——大小卡片的玻璃厚度本来就一样。
                    //
                    // 关掉折射时这一整段（离屏位移采样 + 三通道色散）都不跑，剩下的模糊、
                    // 活力、高光、投影照旧：玻璃还是那块玻璃，只是不再像一片厚透镜。
                    if (refraction) lens(10.dp.toPx(), 14.dp.toPx(), chromaticAberration = true)
                },
                // 玻璃被光打亮的那圈轮廓
                highlight = { Highlight.Default.copy(alpha = 0.65f) },
                // 卡片浮在壁纸上，该有一圈柔和的环境光投影
                shadow = { liquidShadow },
                onDrawSurface = { drawRect(surfaceFill) },
            )
        }
        // 液态玻璃开着、但这一帧没有采样源（不该发生）：退回薄色 + 轮廓 + 投影，
        // 别退回毛玻璃——毛玻璃明显更闷
        liquid -> Modifier
            .appCardShadow(shape = RoundedCornerShape(radius))
            .squircleClip(radius)
            .background(flatGlassFill)
            .squircleBorder(0.9.dp, hairline, radius)
        else -> Modifier
            .then(if (dark) Modifier else Modifier.appCardShadow(shape = RoundedCornerShape(radius)))
            .squircleClip(radius)
            .background(fill)
            .squircleBorder(0.7.dp, hairline, radius)
    }

    val stat = face.stat?.takeIf { it.isNotBlank() }
    // 状态短到 8 个字以内、又不是「一句话型」的功能，才当大字数据排（¥128.50、12 周、3 门）；
    // 其余是一句话（「今日暂无空教室」），压成大字就只剩个糊字
    val figure = stat != null && stat.length <= FIGURE_MAX_CHARS && face.key !in LIST_TILES
    // 数据的颜色：只有「出事了」才换色，其余一律跟正文走——大字本身已经够抢眼了
    val statColor = when (face.tone) {
        HomeTone.ALERT -> if (dark) Color(0xFFFFB4AB) else MiuixTheme.colorScheme.error
        HomeTone.ACCENT -> face.color
        HomeTone.NORMAL, HomeTone.MUTED -> ink
    }

    Box(modifier.then(material)) {
        // 形态按**实时**格数选（§10）：拖动把手缩放时逐帧判断，不等松手才重排。
        // 判据就是「几格宽 × 几格高」，阈值取半格（1.5）：一格见方是小块，一格高是横条，
        // 一格宽是竖条，两格都到是大方块——弹簧动画经过阈值也不会来回抖。
        val shape = when {
            w < 1.5f && h < 1.5f -> HomeTileShape.Icon
            h < 1.5f -> HomeTileShape.Wide
            w < 1.5f -> HomeTileShape.Tall
            else -> HomeTileShape.Large
        }
        Crossfade(targetState = shape, animationSpec = tween(160)) { now ->
            when (now) {
                // 1×1：图标在上、名称在下——正方格的一边长 ~76dp，这两行正好放得下，
                // 所以这一档不再分「有没有文字空间」，永远画名称
                HomeTileShape.Icon -> Column(
                    Modifier.fillMaxSize().padding(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ExpressiveIcon(
                        icon = face.icon,
                        color = iconColor,
                        size = (minOf(w, h) * 34f).coerceIn(28f, 38f).dp,
                        iconSize = (minOf(w, h) * 19f).coerceIn(15f, 21f).dp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        face.title,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }

                // 横条（2×1 起）：图标在左，右边是「小号名称 + 主要数据 + 一行说明」
                HomeTileShape.Wide -> Row(
                    Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExpressiveIcon(icon = face.icon, color = iconColor, size = 30.dp, iconSize = 17.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            face.title,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stat ?: face.subtitle,
                            style = if (figure) {
                                Editorial.figure.copy(fontSize = 19.sp, lineHeight = 23.sp)
                            } else {
                                MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp)
                            },
                            color = statColor,
                            maxLines = if (figure || !face.statDetail.isNullOrBlank()) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 说明只跟着大字数据走：一句话型的状态本身就是完整的一句，再加一行反而碎
                        if (figure && !face.statDetail.isNullOrBlank()) {
                            Text(
                                face.statDetail,
                                fontSize = 9.5.sp,
                                lineHeight = 12.sp,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 竖条（1×2 起）：名称压小、数据放大——高度正好够「小标题 + 一行大字 + 一行说明」。
                // 没有状态的功能（社区、黄页这类）居中放图标 + 名称 + 说明，不留半屏空白
                HomeTileShape.Tall -> if (stat == null) {
                    HomeTileCentered(face, iconColor, ink, muted, iconSize = 30.dp, iconGlyph = 17.dp)
                } else {
                    Column(Modifier.fillMaxSize().padding(10.dp)) {
                        ExpressiveIcon(icon = face.icon, color = iconColor, size = 25.dp, iconSize = 14.dp)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            face.title,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            stat,
                            style = if (figure) {
                                Editorial.figure.copy(fontSize = 19.sp, lineHeight = 24.sp)
                            } else {
                                MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp)
                            },
                            color = statColor,
                            maxLines = if (figure) 1 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!face.statDetail.isNullOrBlank()) {
                            Text(
                                face.statDetail,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                color = muted,
                                maxLines = if (figure) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 大方块（2×2 起）：右上角压一枚几乎看不见的水印图标，数据再放大一档
                HomeTileShape.Large -> if (stat == null) {
                    HomeTileCentered(face, iconColor, ink, muted, iconSize = 42.dp, iconGlyph = 24.dp)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        Icon(
                            face.icon,
                            contentDescription = null,
                            tint = ink.copy(alpha = 0.055f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(64.dp),
                        )
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            Text(
                                face.title,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                stat,
                                style = if (figure) {
                                    Editorial.figure.copy(fontSize = 26.sp, lineHeight = 31.sp)
                                } else {
                                    MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 17.sp)
                                },
                                color = statColor,
                                maxLines = if (figure) 1 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!face.statDetail.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    face.statDetail,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    color = muted,
                                    maxLines = if (figure) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 卡片内容的四种形态，由**实时**格数决定（见 [HomeTileFaceBox] 里的判据）。
 *
 * 与「小组件模式」那套四档（1×1 / 2×1 / 1×2 / 2×2）一一对应，只是这里的格子是自由的，
 * 所以按「几格宽、几格高」分档，而不是按固定的那四个枚举。
 */
private enum class HomeTileShape { Icon, Wide, Tall, Large }

/**
 * 没有状态的功能（社区、黄页这类）：居中一列「图标 + 名称 + 说明」。
 *
 * 这类卡片没数据可放大，「留白 + 居中」本身就是它的版式；字号仍按大块比小块大一号。
 */
@Composable
private fun HomeTileCentered(
    face: HomeServiceRow,
    iconColor: Color,
    ink: Color,
    muted: Color,
    iconSize: Dp,
    iconGlyph: Dp,
) {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExpressiveIcon(icon = face.icon, color = iconColor, size = iconSize, iconSize = iconGlyph)
        Spacer(Modifier.height(8.dp))
        Text(
            face.title,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (face.subtitle.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                face.subtitle,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 状态不超过这么多字才当大号数字排（¥128.50、12 周、3 门），再长就是一句话。 */
private const val FIGURE_MAX_CHARS = 8

/**
 * 状态天生是一句话、不适合当大字数字排的功能（空教室「今日暂无空教室」、通知「3 条未读」）。
 *
 * 名单与「小组件模式」那一套保持一致；用路由 id 而不是字符串字面量，改名时会一起变。
 */
private val LIST_TILES = setOf(
    AppRoute.EmptyRoom.id,
    AppRoute.Lms().id,
    AppRoute.Community.id,
    AppRoute.Games.id,
    AppRoute.Agent.id,
    AppRoute.Notification.id,
    AppRoute.YellowPage.id,
)
