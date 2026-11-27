package com.xjtu.toolbox.data

import com.xjtu.toolbox.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CredentialStoreChannelMigrationTest {

    @Test
    fun giteeAlwaysFallsBackToAliyun() {
        val store = CredentialStore(RuntimeEnvironment.getApplication())

        // Gitee 已下线：旧版本存下的 gitee 一律落到阿里云。
        store.updateChannel = "gitee"
        assertEquals(AppUpdater.CHANNEL_ALIYUN, store.updateChannel)

        store.updateChannel = AppUpdater.CHANNEL_GITHUB
        assertEquals(AppUpdater.CHANNEL_GITHUB, store.updateChannel)

        store.updateChannel = AppUpdater.CHANNEL_CLOUDFLARE
        assertEquals(AppUpdater.CHANNEL_CLOUDFLARE, store.updateChannel)
    }
}
