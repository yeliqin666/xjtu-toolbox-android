package com.xjtu.toolbox.web

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.settings.ChangelogEntry
import com.xjtu.toolbox.settings.ScheduleSourceOption
import com.xjtu.toolbox.settings.SettingsHost
import com.xjtu.toolbox.settings.SettingsRow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Web 这一端的设置页宿主面（`:core` 的 `SettingsScreen` 是共享屏）。 */
internal class WebSettingsHost(
    /** 外观改完之后戳一下外壳（`Main.kt` 重新读一次 `SettingsStore`）。 */
    private val notifyAppearanceChanged: () -> Unit,
    /** 「数据与隐私」那两行归档动作 + 它的一行结果文本。 */
    private val archive: WebArchiveActions,
) : SettingsHost {

    /** 版本那一行：本端没有版本号这个概念（页面是同一个 dist，服务端直接托管它）。 */
    override val versionText: String = "Web 构建（wasm）"

    override val honoredRows: Set<SettingsRow> =
        setOf(SettingsRow.DARK_MODE, SettingsRow.HOME_THEME)

    /** 本端没有更新日志（`:app` 的 `AppChangelog` 在更新域里）⇒ 那一行不画。 */
    override val changelog: List<ChangelogEntry> = emptyList()

    /** 见类 KDoc：本端只有教务一个课表来源 ⇒ 空表 ⇒ 那一行不画。 */
    override val scheduleSourceOptions: List<ScheduleSourceOption> = emptyList()

    /** 「通知与提醒」整组：浏览器里没有系统通知排程这一档 ⇒ 整组不画。 */
    override val noticeSection: (@Composable () -> Unit)? = null

    /** 「数据与隐私」整组：两行归档动作（导出 / 导入数据存档）。 */
    override val dataPrivacySection: (@Composable () -> Unit)? = { WebDataPrivacySection(archive) }

    /** 「更新」整组：本端的更新就是服务端换一份 dist（刷新一次页面） ⇒ 整组不画。 */
    override val updateSection: (@Composable () -> Unit)? = null

    override val onNetworkModeChanged: (() -> Unit)? = null

    override fun onAppearanceChanged() = notifyAppearanceChanged()

    /** 打开外链：浏览器就是 `window.open(..., 'noopener')`（与 `Data.kt` 里那一份同一个实现）。 */
    override val onOpenUrl: (String) -> Unit = { openInNewTab(it) }
}

/** 「数据与隐私」组（接上）：两行归档动作 + 一行结果文本。 */
@Composable
private fun WebDataPrivacySection(archive: WebArchiveActions) {
    SmallTitle("数据与隐私")
    WebSettingsCard {
        ArrowPreference(
            title = "导出数据存档",
            summary = "把本机数据打包成 zip 下载（不含密码等凭据）",
            startAction = { WebSettingsIcon(Icons.Default.Upload) },
            onClick = { archive.export() },
        )
        ArrowPreference(
            title = "导入数据存档",
            summary = "选一份 zip 档案读进来（先自校验，默认不覆盖本机数据）",
            startAction = { WebSettingsIcon(Icons.Default.Download) },
            onClick = { archive.import() },
        )
        archive.status?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** 设置页一行左侧的图标（与 `:core` 设置屏的 `SettingsIcon` 同一视觉）。 */
@Composable
private fun WebSettingsIcon(icon: ImageVector) {
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

/** 设置页分组卡片的外壳（与共享屏的私有 `SettingsCard` 一致）。 */
@Composable
private fun WebSettingsCard(content: @Composable ColumnScope.() -> Unit) {
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
