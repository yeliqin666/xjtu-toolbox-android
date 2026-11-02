package com.xjtu.toolbox.sharedview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjtu.toolbox.bulletin.NoticeDetailScreen
import com.xjtu.toolbox.data.AppearanceSettings
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme

/**
 * 「公告卡 → 公告详情」在 ROM 支持金标联盟一镜到底时的宿主。
 *
 * 只有 [KitOneTake.openBulletinDetail] 成功拿到 KIT `ActivityOptions` 时才会启动本页；
 * 不支持的设备不会走到这里（压 [AppRoute.NoticeDetail] 路由、走 Compose 转场）。
 * 页面内容和路由版是同一个 [NoticeDetailScreen]，避免两套 UI。
 */
class NoticeDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 和 MainActivity 一致的边到边 + 透明导航条，避免起止两端系统栏跳色。
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))

        val notice = noticeFrom(intent)
        val appearance = AppearanceSettings.get(this)
        setContent {
            val darkMode by appearance.darkMode.collectAsStateWithLifecycle()
            val dynamicColor by appearance.dynamicColor.collectAsStateWithLifecycle()
            val customColorHex by appearance.customColorHex.collectAsStateWithLifecycle()
            XJTUToolBoxTheme(
                darkModeOverride = darkMode,
                dynamicColor = dynamicColor,
                customColorHex = customColorHex,
            ) {
                NoticeDetailScreen(
                    notice = notice,
                    onBack = { finish() },
                    onOpenUrl = { url ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_NOTICE_ID = "kit_notice_id"
        private const val EXTRA_NOTICE_LEVEL = "kit_notice_level"
        private const val EXTRA_NOTICE_TITLE = "kit_notice_title"
        private const val EXTRA_NOTICE_BODY = "kit_notice_body"
        private const val EXTRA_NOTICE_URL = "kit_notice_url"

        fun intent(context: Context, notice: AppRoute.NoticeDetail): Intent =
            Intent(context, NoticeDetailActivity::class.java)
                .putExtra(EXTRA_NOTICE_ID, notice.noticeId)
                .putExtra(EXTRA_NOTICE_LEVEL, notice.level)
                .putExtra(EXTRA_NOTICE_TITLE, notice.title)
                .putExtra(EXTRA_NOTICE_BODY, notice.body)
                .putExtra(EXTRA_NOTICE_URL, notice.url)

        fun noticeFrom(intent: Intent): AppRoute.NoticeDetail = AppRoute.NoticeDetail(
            noticeId = intent.getStringExtra(EXTRA_NOTICE_ID).orEmpty(),
            level = intent.getStringExtra(EXTRA_NOTICE_LEVEL).orEmpty().ifEmpty { "info" },
            title = intent.getStringExtra(EXTRA_NOTICE_TITLE).orEmpty(),
            body = intent.getStringExtra(EXTRA_NOTICE_BODY).orEmpty(),
            url = intent.getStringExtra(EXTRA_NOTICE_URL).orEmpty(),
        )
    }
}
