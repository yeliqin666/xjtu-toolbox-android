package com.xjtu.toolbox.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.desktop.archive.ArchiveCheck
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.desktop.archive.DesktopImportOutcome
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.ChangelogEntry
import com.xjtu.toolbox.settings.ScheduleSourceOption
import com.xjtu.toolbox.settings.SettingsHost
import com.xjtu.toolbox.settings.SettingsRow
import com.xjtu.toolbox.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 桌面端的设置偏好存储（进程一份）。
 *
 * 用 `:data` 的 [secureKeyValueStore]（jvm 的 actual = **0600 权限的 Properties 文件**，
 * 与凭据 / cookie / 站点快照同一套落盘口径、同一个数据根目录），文件名与 Android 那份
 * **逐字相同**（`app_settings`）—— 桌面端没有老数据要继承，保持一致是为了两端的键名能一眼对上
 *（与 `JvmCredentialStore` 同一条纪律）。
 *
 * ⚠️ 为什么这里仍用 `secureKeyValueStore` 而不是 `keyValueStore(name)`：`app_settings` 与凭据族
 * 同住一个数据根，走密文那一套 0600 文件；S0 起 `keyValueStore` 的 jvm actual 也真落盘了
 *（同一个根、同一套 `.properties` 形状），但「同一 store 名不得同时以 plain 与 secure 打开」
 *（见 `:core` 的 `KeyValueStoreJvmTest`），这一端按 store 逐个接上落盘实现（凭据 / cookie 已经是这么做的）。
 */
internal val desktopSettings: SettingsStore by lazy {
    SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
}

/**
 * 桌面端的设置页宿主 —— **如实声明这一端有什么**（见 `:core` 的 [SettingsHost] 与 [SettingsRow]）。
 *
 * | | 桌面这一端 |
 * |---|---|
 * | 真会照着办的行 | **深色模式 / 跟随系统取色**：外壳按这两个偏好套 `XJTUToolBoxTheme`（改一下当场就变） |
 * | 「通知与提醒」整组 | `null` —— 桌面没有 WorkManager 排程，也没有申请通知权限的那套流程（设计文档 §6 Stage C 说「小组件用系统通知替代」，那是下一批的事） |
 * | 「数据与隐私」整组 | **S5 起是真组**：两行归档动作（导出数据存档 / 导入数据存档，见下）。Android 那两样（清 `cacheDir` / 崩溃上报）本端仍旧没有 |
 * | 「更新」整组 | `null` —— 这一端是 `.deb` / tar.gz，没有「下载并安装 APK」那条路；更新渠道 / 灰度分桶都是 `:app` 的更新域 |
 * | 「连接模式」 | `null` —— 桌面只按直连走（没有校园网判定与 WebVPN 网关，见 `DesktopAuth` 的「已知缺口」） |
 * | 「当前学期课表来源」 | 空表 —— **课表屏这轮点亮了**（`AppRoute.Schedule` + `DesktopScheduleHost`），
 *   而这一端取数读的确实是同一份 `SettingsStore.scheduleSource`；只是这里还没有改它的入口
 *   （要一对 options + 写入），所以那一行不画 |
 * | 更新日志 | 空表 —— `AppChangelog` 属 `:app` 的更新域，没搬 |
 *
 * ## TODO（桌面侧还缺的宿主面）
 *
 * 1. **外壳只接了深色模式 / 取色 / 首页主题**：`显示常用功能`与`界面风格`（底栏玻璃/经典）
 *    是 `:app` 首页与底栏的宿主面，桌面这一端还没有对应实现 ⇒ 那两行**不画**（列进 honoredRows
 *    才是假装能用）。要接的时候：底栏换成 `:core` 的 `GlassBottomTabs`（`:app` 用的是同一份）；
 *    常用功能那一排要先把点击记录（`ServiceUsageTracker`）搬进共享层。
 * 2. **默认启动 Tab**：桌面底栏固定六格（首页 / 校历 / 图书馆 / 黄页 / 游戏 / 全部），没有「默认进哪个 tab」这一档。
 * 3. **触感反馈**：`HapticsController` 的 jvm actual 是空实现（桌面不震）⇒ 那一行不画。
 * 4. **课表来源 / 课表考勤 / 场馆验证码**：课表屏已经点亮（见上表），但「来源」「考勤角标」这两行
 *    还没有改值的入口，「场馆下单」这一端也不支持（`canBook = false`）⇒ 三行都不画。
 *    ⚠️ 其中「课表来源」的值**已经会被读到**（`:data` 的 `ScheduleSourceRouter` 读同一份 store、
 *    同一个键）—— 只是用户在这一端还没有改它的地方，所以是「不画，但读」，不是「画了没用」。
 * 5. **通知与提醒 / 更新**：见上表（`null`）。
 *
 * ## 本轮接上的：「数据与隐私」组（S5）
 *
 * 它原来是 `null`（这一端没有可清的 Android 应用缓存、没有 `CrashReporter`）。S5 起这一组**真的画**
 * 两行归档动作：导出数据存档（目录形态 + zip 形态写到数据根下）与导入数据存档（扫数据根 + 自校验）。
 * 理由：这两件事在桌面上是真的能做、且做完有可见产物（数据根下两个文件），不是「点了没反应」的开关
 * —— 见 `com.xjtu.toolbox.desktop.archive.DesktopArchiveHost` 的 KDoc（含「合并导入还没落地」那处如实交代）。
 */
internal class DesktopSettingsHost(private val notifyAppearanceChanged: () -> Unit) : SettingsHost {

    /**
     * 版本号那一行：桌面包的版本（构建期烘进 `DesktopBuildConfig`，与 jpackage 的
     * `packageVersion` 同一个来源）。括号里写 `desktop` 是为了与 `:app` 的
     * `v5.1.1 (471)` 那一行一眼分得开 —— 这一端没有 `VERSION_CODE`。
     */
    override val versionText: String = "v${DesktopBuildConfig.VERSION_NAME} (desktop)"

    /**
     * 真会照着办的行。
     *
     *  - [SettingsRow.DARK_MODE] / [SettingsRow.DYNAMIC_COLOR]：外壳按这两个偏好套 `XJTUToolBoxTheme`；
     *  - [SettingsRow.HOME_THEME]：**本轮接上** —— 桌面现在有首页了（`AppRoute.Main` →
     *    `DesktopHomeScreen`），它读的就是这个键（`SettingsStore.homeTheme`，与 `:app` 同一个键、
     *    同一个值域）：改一下再回首页，两套布局真的会换。
     *
     * `显示常用功能`（[SettingsRow.QUICK_ACTIONS]）**不列**：桌面没有 `ServiceUsageTracker` 那份
     * 点击记录，首页那一排「常用功能」在桌面上压根不画（见 `DesktopHomeScreen` 的如实降级那段）
     * —— 开关拨了也看不到任何变化，那就是假装能用。
     */
    override val honoredRows: Set<SettingsRow> =
        setOf(SettingsRow.DARK_MODE, SettingsRow.DYNAMIC_COLOR, SettingsRow.HOME_THEME)

    override val changelog: List<ChangelogEntry> = emptyList()

    override val scheduleSourceOptions: List<ScheduleSourceOption> = emptyList()

    override val noticeSection: (@Composable () -> Unit)? = null

    override val dataPrivacySection: (@Composable () -> Unit)? = { DesktopDataPrivacySection() }

    override val updateSection: (@Composable () -> Unit)? = null

    override val onNetworkModeChanged: (() -> Unit)? = null

    override fun onAppearanceChanged() = notifyAppearanceChanged()

    /** 打开外链：桌面用 `Main.kt` 里那个 `openInBrowser`（`java.awt.Desktop.browse`）。 */
    override val onOpenUrl: (String) -> Unit = { openInBrowser(it) }
}

/**
 * 「数据与隐私」组（S5 接上）：两行归档动作 + 一行结果文本。
 *
 * ## 一次性导入：本宿主把写侧真的接上了（§6.4）
 *
 * §6.2 的完整链路是「有档案才跑 → staging 落地 + 自校验 → 逐 store 合并 → 任一步失败则端内零变化」。
 * 逐 store 合并是 `:core` 的 [StoreArchive.import]（S1b），本工单把**写侧**（[DesktopArchiveImportTarget]）
 * 接上去，所以现在：
 *
 *  - [pendingArchives] —— 扫数据根（§6.2 桌面那一行的触发点），有档案才继续；
 *  - [checkArchive] —— 自校验：调 `:core` 的 `StoreArchive.inspect`（S1b 收口），只补本端的
 *    「档案路径 → 数据根文件」那一列（A13）；
 *  - [maybeImportPendingArchive] —— 真的导：自校验 → §6.4 合并 → 落「已导入」标记
 *    （[DesktopArchiveImport]），坏档案在写第一笔之前就返回 [ArchiveCheck.Failed] ⇒ 端内零改动（A7）。
 *
 * 结果文本以卡片里的一行给出（桌面没有 `Toast`，`android.widget.Toast` 在 JVM 上不存在）。
 */
@Composable
private fun DesktopDataPrivacySection() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SmallTitle("数据与隐私")
    SettingsCard {
        ArrowPreference(
            title = "导出数据存档",
            summary = "写到数据根下的 ${DesktopArchiveHost.ARCHIVE_DIR}/ 目录与 ${DesktopArchiveHost.ARCHIVE_ZIP_NAME}（不含密码等凭据）",
            startAction = { SettingsIcon(Icons.Default.Upload) },
            onClick = {
                if (busy) return@ArrowPreference
                busy = true
                status = "导出中…"
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching { DesktopArchiveHost.exportToDataRoot() }.fold(
                            onSuccess = {
                                "已导出 ${it.entries} 个条目到 ${it.directory.absolutePath}，另有 ${it.zip.name}"
                            },
                            onFailure = { "导出失败：${it.message ?: it::class.simpleName}" },
                        )
                    }
                    busy = false
                }
            },
        )
        ArrowPreference(
            title = "导入数据存档",
            summary = "扫数据根下的 ${DesktopArchiveHost.ARCHIVE_DIR}/ 目录与 *.zip，先自校验再合并",
            startAction = { SettingsIcon(Icons.Default.Download) },
            onClick = {
                if (busy) return@ArrowPreference
                busy = true
                scope.launch {
                    status = withContext(Dispatchers.IO) { importStatusText() }
                    busy = false
                }
            },
        )
        status?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 导入那一行的结果文本（纯函数，供屏与测试共用同一份措辞）：
 * 数据根下没有档案、真导入了、这一份已经导过、档案不可用 —— 四种如实分开说。
 *
 * 措辞仍以「发现待导入档案 <名>」开头（存档被发现的这件事没变），后面接真的结果：
 * `:core` 的 [StoreArchive.import] 写了几笔、这一份是否已经导过、或者为什么不可用。
 */
internal fun importStatusText(): String {
    val pending = DesktopArchiveHost.maybeImportPendingArchive()
        ?: return "数据根下没有待导入的档案"
    val prefix = "发现待导入档案 ${pending.path.name}"
    return when (val outcome = pending.outcome) {
        is DesktopImportOutcome.Imported ->
            "$prefix：已交给 :core 的 StoreArchive.import 按 §6.4 合并 —— " +
                "写入 ${outcome.result.keysWritten} 个键 / 保持 ${outcome.result.keysKept} 个 / " +
                "写回 ${outcome.result.filesWritten} 个文件"
        is DesktopImportOutcome.AlreadyImported -> "$prefix：这份档案已经导过（标记与它一致），本次未改动任何数据"
        is DesktopImportOutcome.Failed -> "$prefix 不可用：${outcome.reason}（端内零变化）"
    }
}

/**
 * 设置页一行左侧的图标（与 `:core` 设置屏里那份私有的 `SettingsIcon` 同一视觉：圆形色块 + 18dp 图标）。
 * 跨模块用不了共享屏里的私有 composable，所以这里起一份（`:app` 的通知组也是这么做的）。
 */
@Composable
private fun SettingsIcon(icon: ImageVector) {
    val color = MiuixTheme.colorScheme.primary
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
    Spacer(Modifier.width(12.dp))
}

/** 设置页一个分组卡片的外壳（与共享屏里那份私有 `SettingsCard` 同一条口径）。 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(content = content)
    }
}
