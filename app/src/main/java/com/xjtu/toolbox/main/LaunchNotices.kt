package com.xjtu.toolbox.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinLaunchDialog
import com.xjtu.toolbox.bulletin.BulletinLevel
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.bulletin.BulletinStore
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackStore
import com.xjtu.toolbox.update.AppChangelog
import com.xjtu.toolbox.update.AppUpdateInfo
import com.xjtu.toolbox.update.AppUpdater
import com.xjtu.toolbox.update.AutoUpdateDialog
import com.xjtu.toolbox.update.UpdateNoticeDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 启动时要给用户看的东西：远端公告（首页堆叠条 + 最重要的那条弹框）、自动检查到的新版本、
 * 本地「这次更新了什么」。启动必检，不看「启动时检查更新」开关。
 */
@Stable
class LaunchNotices(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val scope: CoroutineScope,
) {
    private val bulletinStore = BulletinStore(context)

    /** 上一次运行的版本；全新安装为 null。 */
    val previousRunVersion: String? = credentialStore.lastRunVersion

    /** 自上次已见之后的全部新版本更新日志，堆叠展示。 */
    val pendingChangelog = AppChangelog.since(credentialStore.lastSeenChangelogVersion ?: previousRunVersion)

    /**
     * 全新安装没有「上一版」可言，给第一次打开的人看更新公告是噪音。
     * 这里不写 lastSeenChangelogVersion，所以下次真正升级时照常提示。
     */
    val showChangelog = mutableStateOf(pendingChangelog.isNotEmpty() && previousRunVersion != null)

    /** 首页顶部的公告堆叠条。 */
    var heroBulletins by mutableStateOf<List<Bulletin>>(emptyList())
        private set

    /** 启动时弹框的那一条公告。 */
    var launchDialogBulletin by mutableStateOf<Bulletin?>(null)
        private set
    val showBulletinDialog = mutableStateOf(false)

    /** 正在弹框展示的新版本；null = 不弹。 */
    var autoUpdate by mutableStateOf<AppUpdateInfo?>(null)
        private set

    /** 公告与更新都查完了，才轮到本地更新日志弹框（同一时刻只弹一层）。 */
    var checkFinished by mutableStateOf(false)
        private set

    private var pendingUpdate: AppUpdateInfo? = null

    suspend fun check() {
        if (credentialStore.lastRunVersion != BuildConfig.VERSION_NAME) {
            credentialStore.lastRunVersion = BuildConfig.VERSION_NAME
        }
        applyHero(bulletinStore.peekCached(), null)

        val fetched = runCatching { BulletinApi.fetch() }.getOrNull()
        if (fetched != null) bulletinStore.cachedJson = fetched.rawJson
        val remoteItems = fetched?.items ?: bulletinStore.peekCached()

        var update: AppUpdateInfo? = null
        if (System.currentTimeMillis() - credentialStore.lastAutoUpdateCheckAt >= AppUpdater.AUTO_CHECK_INTERVAL_MS) {
            try {
                update = AppUpdater.check(
                    channel = credentialStore.updateChannel,
                    includePreview = credentialStore.receivePreviewUpdates,
                    rolloutId = credentialStore.rolloutId,
                )
                credentialStore.lastAutoUpdateCheckAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w("AppUpdater", "startup update check failed", e)
            }
        }
        if (update != null) pendingUpdate = update
        applyHero(remoteItems, update)
        val picked = BulletinRules.pickLaunchDialog(heroBulletins)
        if (picked != null) {
            launchDialogBulletin = picked
            showBulletinDialog.value = true
        } else if (update != null && !credentialStore.isUpdateNoticeSeen(autoSeenKey(update))) {
            autoUpdate = update
        }
        checkFinished = true
    }

    fun onHeroTap(bulletin: Bulletin) {
        when {
            bulletin.isPoll -> {
                launchDialogBulletin = bulletin
                showBulletinDialog.value = true
            }
            bulletin.level == BulletinLevel.FORCE_UPDATE ||
                bulletin.level == BulletinLevel.UPDATE ||
                bulletin.synthesized -> openPendingUpdate(bulletin.url)
            !bulletin.url.isNullOrBlank() -> openUrl(bulletin.url)
        }
    }

    fun dismissHero(bulletin: Bulletin) {
        when {
            bulletin.synthesized -> credentialStore.markUpdateNoticeSeen(bulletin.id)
            bulletin.level == BulletinLevel.CRITICAL -> bulletinStore.ack(bulletin.id)
            bulletin.level == BulletinLevel.FORCE_UPDATE -> bulletinStore.snooze(bulletin.id)
            else -> bulletinStore.dismiss(bulletin.id)
        }
        refreshHero()
    }

    internal fun dismissBulletinDialog(bulletin: Bulletin) {
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) {
            bulletinStore.snooze(bulletin.id)
            refreshHero()
        }
        closeBulletinDialog()
    }

    internal fun confirmBulletinDialog(bulletin: Bulletin) {
        closeBulletinDialog()
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) openPendingUpdate(bulletin.url)
    }

    /**
     * 跟 FeedbackPromptSheet 一个原则：提交失败静默丢弃，不为这一下额外打扰用户。
     * 本地先标记已答，不等网络结果——避免提交失败时反复重弹同一条投票。
     */
    internal fun submitPoll(bulletin: Bulletin, selected: List<String>) {
        scope.launch {
            runCatching {
                FeedbackApi.submit(
                    ticket = FeedbackStore.newTicket(),
                    category = "投票·${bulletin.id}",
                    content = selected.joinToString("、"),
                    contact = "",
                    anonId = FeedbackStore.anonId(context),
                )
            }
        }
        bulletinStore.ack(bulletin.id)
        refreshHero()
        closeBulletinDialog()
    }

    internal fun dismissAutoUpdate() {
        autoUpdate?.let { credentialStore.markUpdateNoticeSeen(autoSeenKey(it)) }
        autoUpdate = null
        refreshHero()
    }

    internal fun dismissChangelog() {
        credentialStore.lastSeenChangelogVersion = BuildConfig.VERSION_NAME
        showChangelog.value = false
    }

    private fun closeBulletinDialog() {
        showBulletinDialog.value = false
        launchDialogBulletin = null
    }

    private fun refreshHero() = applyHero(bulletinStore.peekCached(), pendingUpdate)

    private fun applyHero(remote: List<Bulletin>, update: AppUpdateInfo?) {
        val synthetic = update?.let { info ->
            BulletinRules.syntheticUpdate(info.version, info.channel)
                .takeUnless { credentialStore.isUpdateNoticeSeen(it.id) }
        }
        heroBulletins = BulletinRules.visible(
            items = remote + listOfNotNull(synthetic),
            now = Instant.now(),
            currentVersion = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            dismissedIds = bulletinStore.dismissedIds,
            ackedIds = bulletinStore.ackedIds,
            snoozedIds = BulletinStore.sessionSnoozedIds(),
        )
    }

    /** 强制更新是公告指定要升到正式版，不能把开了预览开关的人引到预览版上。 */
    private fun openPendingUpdate(fallbackUrl: String?) {
        pendingUpdate?.let {
            autoUpdate = it
            return
        }
        scope.launch {
            runCatching { AppUpdater.fetchLatest(credentialStore.updateChannel) }
                .onSuccess { update ->
                    pendingUpdate = update
                    autoUpdate = update
                }
                .onFailure {
                    val page = fallbackUrl ?: AppUpdater.releasesPageUrl(credentialStore.updateChannel)
                    if (!openUrl(page)) {
                        Toast.makeText(context, "检查更新失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun openUrl(url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private fun autoSeenKey(update: AppUpdateInfo) = "auto_${update.channel}_${update.version}"
}

@Composable
fun rememberLaunchNotices(credentialStore: CredentialStore): LaunchNotices {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val notices = remember { LaunchNotices(context, credentialStore, scope) }
    LaunchedEffect(notices) { notices.check() }
    return notices
}

/**
 * 启动弹框。WindowBottomSheet 同时只能稳妥挂一个：冷启动若强制更新和重要通知各弹一层，
 * 后开的会把先开的顶掉。首页堆叠条照常全显示，框只弹优先级最高的那条。
 */
@Composable
fun LaunchNoticeDialogs(notices: LaunchNotices) {
    val bulletin = notices.launchDialogBulletin
    val update = notices.autoUpdate
    when {
        notices.showBulletinDialog.value && bulletin != null -> BulletinLaunchDialog(
            bulletin = bulletin,
            show = notices.showBulletinDialog,
            onDismiss = { notices.dismissBulletinDialog(bulletin) },
            onPrimary = { notices.confirmBulletinDialog(bulletin) },
            onSubmitPoll = { notices.submitPoll(bulletin, it) },
        )
        update != null -> AutoUpdateDialog(
            version = update.version,
            body = update.notes,
            downloadUrl = update.downloadUrl,
            releaseUrl = update.releaseUrl,
            channelLabel = update.channelLabel,
            isPreview = update.isPreview,
            onDismiss = notices::dismissAutoUpdate,
        )
        notices.checkFinished && notices.showChangelog.value -> UpdateNoticeDialog(
            entries = notices.pendingChangelog,
            show = notices.showChangelog,
            fromVersion = notices.previousRunVersion?.takeIf { it != BuildConfig.VERSION_NAME },
            onDismiss = notices::dismissChangelog,
        )
    }
}
