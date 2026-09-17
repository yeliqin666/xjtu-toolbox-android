@file:OptIn(ExperimentalLayoutApi::class)

package com.xjtu.toolbox.social

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.qrlogin.QrScannerView
import com.xjtu.toolbox.util.QrBitmap
import com.xjtu.toolbox.util.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 匹配交友。
 *
 * 全程离线：把自己的档案压成一张二维码给朋友扫（或者一段文字让他粘），
 * 算契合度这一步在本机完成。没有服务器、没有账号，我们一条数据都不经手——
 * 给谁看、看多少由用户自己决定，见 [MatchProfile] 的说明。
 *
 * 结果分两块：上面一个分数回答"约不约得上"，下面一串**交集**回答"我俩有什么共同点"。
 * 后者才是好玩的地方——同一栋楼擦肩而过、同一场考试、同一个老师、同一本教材，
 * 都是本机缓存里本来就有、只是从没被拿出来对过的东西。
 */
@Composable
fun MatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    var nickname by remember { mutableStateOf("") }
    // 名字默认填本机档案里的真名，但用户一旦动过就不再回填——
    // 否则他刚改成外号，档案一加载又被覆盖回去。
    var nicknameEdited by remember { mutableStateOf(false) }
    var dims by remember { mutableStateOf(MatchProfile.Dimensions()) }
    var dietInput by remember { mutableStateOf("") }
    var local by remember { mutableStateOf(MatchData.Local()) }
    var loading by remember { mutableStateOf(true) }

    var showShareSettings by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var theirCode by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<MatchProfile.Result?>(null) }
    var theirName by remember { mutableStateOf("") }
    var decodeError by remember { mutableStateOf<String?>(null) }

    // 全部读本地缓存，这个功能不为自己发任何请求，也不触发登录。
    // 哪个页面没逛过就没有对应的数据，那一项会禁用并说明原因。
    LaunchedEffect(Unit) {
        local = withContext(Dispatchers.IO) { MatchData.read(context) }
        loading = false
    }
    LaunchedEffect(local.profile?.name) {
        if (!nicknameEdited) {
            local.profile?.name?.takeIf { it.isNotBlank() }?.let { nickname = it }
        }
    }

    val myProfile = remember(nickname, local, dietInput, dims) {
        MatchProfile.build(
            local = local,
            nickname = nickname,
            dietTags = dietInput.split(Regex("""[,，、\s]+""")).filter { it.isNotBlank() }.toSet(),
            dims = dims,
        )
    }
    val myCode = remember(myProfile) { MatchProfile.encode(myProfile) }

    fun match(raw: String) {
        val compact = raw.filterNot { it.isWhitespace() }
        val theirs = MatchProfile.decode(compact)
        when {
            theirs == null -> {
                decodeError = "这段码读不出来。确认是对方在这个页面里生成的，而且他的版本不比你旧。"
                result = null
            }
            // 自己跟自己算必然是满分，与其展示一个假的 100%，不如说穿。
            compact == myCode -> {
                decodeError = "这是你自己的码。跟自己当然处处合拍，扫对方那张。"
                result = null
            }
            else -> {
                decodeError = null
                theirName = theirs.nickname.ifBlank { "对方" }
                result = MatchProfile.compare(myProfile, theirs)
            }
        }
    }

    // 对方不在跟前时，让他把码截图发过来，这边从相册选一张就行——
    // 比让用户去别的 App 长按识别、再复制一大段文字回来靠谱得多。
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val scanned = withContext(Dispatchers.IO) {
                runCatching {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    // getPixels 读不了硬件位图，解码时就得要一张软件位图。
                    // 顺手按长边缩一次：相册里的截图动辄四千像素宽，整张解又慢又不准。
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val edge = maxOf(info.size.width, info.size.height)
                        if (edge > 1600) {
                            val ratio = 1600f / edge
                            decoder.setTargetSize(
                                (info.size.width * ratio).toInt().coerceAtLeast(1),
                                (info.size.height * ratio).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                    QrBitmap.read(bitmap).also { bitmap.recycle() }
                }.getOrNull()
            }
            if (scanned == null) {
                decodeError = "图里没找到二维码。换一张完整点、清楚点的截图试试。"
                result = null
            } else {
                theirCode = scanned
                match(scanned)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "匹配交友",
                    largeTitle = "匹配交友",
                    color = MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                if (loading) {
                    Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                        LinearProgressIndicator(Modifier.width(120.dp))
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .overScrollVertical(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            MyCodeCard(
                                code = myCode,
                                sharedCount = myProfile.sharedCount,
                                onEditShare = { showShareSettings = true },
                                onCopy = { clipboard.setText(AnnotatedString(myCode)) },
                            )
                        }
                        item {
                            TheirCodeCard(
                                code = theirCode,
                                onCode = { theirCode = it; decodeError = null },
                                error = decodeError,
                                onScan = { scanning = true },
                                onPickImage = {
                                    picker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onCompute = { match(theirCode) },
                            )
                        }
                        result?.let { r ->
                            item {
                                ResultCard(
                                    theirName = theirName,
                                    result = r,
                                    onCopy = {
                                        clipboard.setText(
                                            AnnotatedString(MatchProfile.summaryText(theirName, r))
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                // OverlayDialog 得写在 Scaffold 的 content 里：它靠 Scaffold 提供的弹窗宿主渲染，
                // 挂在外面会注册进一个空列表，点了没反应。
                if (showShareSettings) {
                    ShareSettingsDialog(
                        local = local,
                        nickname = nickname,
                        onNickname = { nickname = it; nicknameEdited = true },
                        dims = dims,
                        onDims = { dims = it },
                        dietInput = dietInput,
                        onDietInput = { dietInput = it },
                        onDismiss = { showShareSettings = false },
                    )
                }
            }
        }

        if (scanning) {
            ScannerOverlay(
                onResult = { scanned ->
                    scanning = false
                    theirCode = scanned
                    match(scanned)
                },
                onClose = { scanning = false },
            )
        }
    }
}

// ── 交换 ─────────────────────────────────────────────────

/**
 * 我的码：一张二维码 + 一段文字兜底。
 *
 * 二维码只在扫得动的长度里给。密到 [QrBitmap.MAX_SCANNABLE] 以上的码摆出来，
 * 只会让两个人举着手机对半天——那时候不如直说"少开几项，或者复制文字"。
 */
@Composable
private fun MyCodeCard(
    code: String,
    sharedCount: Int,
    onEditShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val scannable = code.length <= QrBitmap.MAX_SCANNABLE
    val qr by produceState<Bitmap?>(null, code, scannable) {
        value = if (!scannable) null
        else withContext(Dispatchers.Default) { QrBitmap.generate(code, 720) }
    }

    SectionCard("我的码") {
        if (scannable) {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                val bitmap = qr
                if (bitmap == null) {
                    Box(Modifier.size(232.dp), Alignment.Center) {
                        LinearProgressIndicator(Modifier.width(80.dp))
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "我的匹配交友二维码",
                        modifier = Modifier
                            .size(232.dp)
                            .clip(RoundedCornerShape(12.dp))
                            // 二维码得是深色印在浅色上才扫得出，这一块不跟随深色主题。
                            .background(Color.White)
                            .padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (code.length <= QrBitmap.COMFORTABLE) "让对方用「扫一扫」扫这张"
                else "开的项多，码有点密，扫的时候凑近点",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                "开的项太多，二维码密到扫不出来了。少开几项，或者把下面的文字发给对方。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("分享了 $sharedCount 项", style = MiuixTheme.textStyles.body2)
                Text(
                    // 维度越多码越长。与其等用户粘到一半发现聊天框塞不下，不如把长度摆在这。
                    "文字 ${code.length} 字",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextButton(text = "改", onClick = onEditShare)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(text = "复制文字", onClick = onCopy, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TheirCodeCard(
    code: String,
    onCode: (String) -> Unit,
    error: String?,
    onScan: () -> Unit,
    onPickImage: () -> Unit,
    onCompute: () -> Unit,
) {
    SectionCard("对方的码") {
        TextButton(
            text = "扫一扫",
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(text = "从相册选图", onClick = onPickImage, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = MiuixTheme.colorScheme.dividerLine)
            Text(
                "  也可以粘一段文字  ",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            HorizontalDivider(Modifier.weight(1f), color = MiuixTheme.colorScheme.dividerLine)
        }
        Spacer(Modifier.height(10.dp))
        TextField(
            value = code,
            onValueChange = onCode,
            label = "把对方发来的那段粘进来",
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            text = "算一算",
            onClick = onCompute,
            modifier = Modifier.fillMaxWidth(),
            enabled = code.isNotBlank(),
        )
    }
}

/** 全屏取景。相机权限就地要，不必为一次扫码把用户丢去系统设置再走回来。 */
@Composable
private fun ScannerOverlay(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            QrScannerView(Modifier.fillMaxSize(), onResult = onResult)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (granted) "对准对方屏幕上的二维码" else "得先允许用一下相机",
                style = MiuixTheme.textStyles.body2,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            if (!granted) {
                TextButton(
                    text = "允许使用相机",
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(Modifier.height(8.dp))
            }
            TextButton(text = "取消", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── 分享哪些 ──────────────────────────────────────────────

/**
 * 分享项开关。
 *
 * 摆在弹窗里而不是页面上：十来个开关铺开占掉整整一屏，而这些开关多数人只在
 * 第一次用的时候动一动，之后每次进来想看的是码和结果。
 */
@Composable
private fun ShareSettingsDialog(
    local: MatchData.Local,
    nickname: String,
    onNickname: (String) -> Unit,
    dims: MatchProfile.Dimensions,
    onDims: (MatchProfile.Dimensions) -> Unit,
    dietInput: String,
    onDietInput: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasSchedule = local.courses.isNotEmpty()
    val hasBuildings = hasSchedule && local.courses.any { MatchProfile.buildingOf(it.location) != null }
    val hasTeachers = local.courses.any { it.teacher.isNotBlank() }
    val noSchedule = "还没读到课表，去日程页转一圈就有了"

    BackHandler(onBack = onDismiss)
    OverlayDialog(
        show = true,
        title = "分享哪些",
        summary = "都在你手机上算，不上传。关掉的项不会写进码里，对方看不到。",
        onDismissRequest = onDismiss,
    ) {
        Column {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TextField(
                    value = nickname,
                    onValueChange = onNickname,
                    label = "名字（对方会看到）",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                GroupLabel("课")
                DimRow(
                    "空课时间",
                    if (hasSchedule) "本学期 ${local.courses.size} 节课，用来算你俩什么时候都空" else noSchedule,
                    dims.schedule, hasSchedule,
                ) { onDims(dims.copy(schedule = it)) }
                DimRow(
                    "上的课",
                    if (hasSchedule) "课名和课程号，成绩不在里面" else noSchedule,
                    dims.sameCourses, hasSchedule,
                ) { onDims(dims.copy(sameCourses = it)) }
                DimRow(
                    "教学楼",
                    if (hasBuildings) "只到楼，不到教室，用来看你俩每周在哪撞得上" else "课表里没写教室",
                    dims.buildings, hasBuildings,
                ) { onDims(dims.copy(buildings = it)) }
                DimRow(
                    "老师",
                    if (hasTeachers) "只有姓名" else "课表里没写老师",
                    dims.teachers, hasTeachers,
                ) { onDims(dims.copy(teachers = it)) }
                DimRow(
                    "以前的课",
                    if (local.pastCourseCodes.isEmpty()) "缓存里只有这一个学期"
                    else "另外 ${local.pastTermCount} 个学期的 ${local.pastCourseCodes.size} 门，只带课程号，不带课名",
                    dims.pastCourses, local.pastCourseCodes.isNotEmpty(),
                ) { onDims(dims.copy(pastCourses = it)) }
                DimRow(
                    "考试",
                    if (local.exams.isEmpty()) "还没读到考试表，去日程页转一圈"
                    else "${local.exams.size} 场考试的科目和日期，考场座位不带",
                    dims.exams, local.exams.isNotEmpty(),
                ) { onDims(dims.copy(exams = it)) }
                DimRow(
                    "教材",
                    if (local.textbooks.isEmpty()) "还没读到教材，去日程页的教材看一眼"
                    else "${local.textbooks.size} 本书名",
                    dims.textbooks, local.textbooks.isNotEmpty(),
                ) { onDims(dims.copy(textbooks = it)) }

                GroupLabel("吃")
                DimRow(
                    "常在几点吃饭",
                    if (local.diningHourCounts.isEmpty()) "还没读到消费记录，去校园卡页看一眼"
                    else "只有钟点，没有金额和商户",
                    dims.diningHours, local.diningHourCounts.isNotEmpty(),
                ) { onDims(dims.copy(diningHours = it)) }
                DimRow(
                    "常去哪个食堂",
                    if (local.canteens.isEmpty()) "还没读到消费记录，去校园卡页看一眼"
                    else "只到食堂，不到窗口：${local.canteens.joinToString("、")}",
                    dims.canteens, local.canteens.isNotEmpty(),
                ) { onDims(dims.copy(canteens = it)) }
                DimRow("口味", "自己写，想写什么写什么", dims.dietTags, true) {
                    onDims(dims.copy(dietTags = it))
                }
                if (dims.dietTags) {
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = dietInput,
                        onValueChange = onDietInput,
                        label = "辣、面食、咖啡、不吃香菜…",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                GroupLabel("人")
                DimRow(
                    "年级 专业 校区 生源地",
                    if (local.profile == null) "还没读到个人信息，回首页看一眼"
                    else "不带学号，只有年级、专业、书院、校区和生源地省份",
                    dims.identity, local.profile != null,
                ) { onDims(dims.copy(identity = it)) }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                text = "好了",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun DimRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 没数据的项禁用而不是藏起来：让人知道这一项存在，以及为什么现在用不了。
        Switch(checked = checked && enabled, enabled = enabled, onCheckedChange = onChange)
    }
}

// ── 结果 ─────────────────────────────────────────────────

@Composable
private fun ResultCard(
    theirName: String,
    result: MatchProfile.Result,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (result.empty) {
                Text("和 $theirName", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "你俩没有一项是都愿意分享的，没得比。让对方多开几项试试。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                return@Column
            }

            if (result.scored) {
                ScoreRing(result.overall, result.verdict, theirName)
            } else {
                // 双方都没分享课表时没有分数可给。硬画一个 0 分的圆环等于说"你俩不合"，
                // 而实际情况只是没得比——下面的交集照常展示。
                Text("和 $theirName 的交集", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "谁都没分享课表，分数算不出来。不过还是翻到了这些：",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 校区不同先摆最上面：共同空闲再高也约不上，这时百分比是误导。
            result.blocker?.let { b ->
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "⚠️  $b",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (result.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.notes.forEach { TagChip(it, accent = true) }
                }
            }

            if (result.discoveries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("翻到的交集", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "这些不算分。有就是有，没有也不说明你俩不合。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                result.discoveries.forEachIndexed { i, d -> DiscoveryRow(d, i) }
            }

            result.overlapGrid?.let { grid ->
                Spacer(Modifier.height(16.dp))
                Text("什么时候能凑一块", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OverlapGrid(grid)
                Spacer(Modifier.height(8.dp))
                GridLegend(hasEncounters = result.encounters.isNotEmpty())
                Spacer(Modifier.height(10.dp))
                if (result.freeBlocks.isEmpty()) {
                    Text(
                        "工作日没有连着两节都空的时候，只能挤课间，或者约周末。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    result.freeBlocks.take(3).forEach { FreeBlockRow(it) }
                }
            }

            if (result.facets.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("分数是怎么来的", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                result.facets.forEachIndexed { index, f -> FacetBar(f, index) }
                Spacer(Modifier.height(6.dp))
                Text(
                    // 说清这个数是怎么来的。只对双方都开的项算，
                    // 否则"对方注重隐私"会被读成"你俩不合"。
                    "只看这 ${result.facets.size} 项，共同空闲的分量最重。" +
                        "空闲只算周一到周五——周末谁都空，算进去分不出高低。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.height(12.dp))
            TextButton(text = "复制结果", onClick = onCopy, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 一条交集。标题一行，具体名字排成胶囊。 */
@Composable
private fun DiscoveryRow(d: MatchProfile.Discovery, index: Int) {
    var appeared by remember(d.title) { mutableStateOf(false) }
    LaunchedEffect(d.title) { appeared = true }
    AnimatedVisibility(visible = appeared, enter = fadeIn(tween(delayMillis = 60 * index)) + expandVertically()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text("${d.emoji}  ${d.title}", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
            if (d.items.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.items.take(8).forEach { TagChip(it) }
                    if (d.items.size > 8) TagChip("还有 ${d.items.size - 8} 项")
                }
            }
        }
    }
}

/** 大圆环。数字从 0 扫到分数，是这个页面唯一一处"发生了什么"的动效。 */
@Composable
private fun ScoreRing(score: Int, verdict: String, theirName: String) {
    val sweep by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "matchSweep",
    )
    val shown by animateFloatAsState(
        targetValue = score.toFloat(),
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "matchNumber",
    )
    val track = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ink = scoreColor(score)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(148.dp), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = ink,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${shown.toInt()}",
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
                Text(
                    "分",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(verdict, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        Text(
            "和 $theirName",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 7 列 × 11 行的叠加网格。
 *
 * 四档：都空、一个人空、同一栋楼、都有课。"同一栋楼"单独一档，是因为它是这张图里
 * 唯一一件意料之外的事——两个人同一时刻待在同一栋楼，值得比"都有课"更显眼。
 */
@Composable
private fun OverlapGrid(grid: String) {
    val both = MiuixTheme.colorScheme.primary
    val one = MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
    val same = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    val none = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.width(18.dp))
            MatchProfile.DAY_NAMES.forEach { day ->
                Text(
                    day.removePrefix("周"),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        for (section in 1..MatchProfile.SECTIONS) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$section",
                    modifier = Modifier.width(18.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                for (day in 0 until MatchProfile.DAYS) {
                    val cell = grid[day * MatchProfile.SECTIONS + (section - 1)]
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when (cell) {
                                    MatchProfile.Cell.BOTH_FREE -> both
                                    MatchProfile.Cell.ONE_FREE -> one
                                    MatchProfile.Cell.SAME_BUILDING -> same
                                    else -> none
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLegend(hasEncounters: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot(MiuixTheme.colorScheme.primary, "都空")
        LegendDot(MiuixTheme.colorScheme.primary.copy(alpha = 0.18f), "一个人空")
        if (hasEncounters) LegendDot(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.32f), "同一栋楼")
        LegendDot(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), "都有课")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun FreeBlockRow(block: MatchProfile.FreeBlock) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                MatchProfile.DAY_NAMES[block.day],
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${block.from}-${block.to} 节，连着 ${block.length} 节都空",
                style = MiuixTheme.textStyles.footnote1,
            )
            Text(
                XjtuTime.getTimeRangeStr(block.from, block.to),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 分项条。逐条错开进场，让"一项项算出来"这件事看得见。 */
@Composable
private fun FacetBar(facet: MatchProfile.Facet, index: Int) {
    var appeared by remember(facet.label) { mutableStateOf(false) }
    LaunchedEffect(facet.label, facet.score) { appeared = true }
    val fraction by animateFloatAsState(
        targetValue = if (appeared) facet.score / 100f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 120 * index, easing = LinearOutSlowInEasing),
        label = "facet_${facet.label}",
    )
    val ink = scoreColor(facet.score)

    AnimatedVisibility(visible = appeared, enter = fadeIn() + expandVertically()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${facet.emoji}  ${facet.label}", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.weight(1f))
                Text(
                    "${facet.score}%",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ink)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                facet.detail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun TagChip(label: String, accent: Boolean = false) {
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (accent) MiuixTheme.colorScheme.tertiaryContainer
                else MiuixTheme.colorScheme.secondaryContainer
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (accent) MiuixTheme.colorScheme.onTertiaryContainer
            else MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 低分不该跟高分一个颜色，但也不该用 error 红——那是"出错了"，不是"约不上"。 */
@Composable
private fun scoreColor(score: Int): Color = when {
    score >= 70 -> MiuixTheme.colorScheme.primary
    score >= 40 -> MiuixTheme.colorScheme.primaryVariant
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
