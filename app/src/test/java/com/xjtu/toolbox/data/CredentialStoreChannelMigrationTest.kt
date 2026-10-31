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
    fun oldGiteeMigratesToAliyunOnlyOnce() {
        val store = CredentialStore(RuntimeEnvironment.getApplication())

        // 模拟旧版本存下的默认值：第一次读取应迁到阿里云。
        store.updateChannel = AppUpdater.CHANNEL_GITEE
        assertEquals(AppUpdater.CHANNEL_ALIYUN, store.updateChannel)

        // 迁移只做一次；用户之后主动选 Gitee 应保持。
        store.updateChannel = AppUpdater.CHANNEL_GITEE
        assertEquals(AppUpdater.CHANNEL_GITEE, store.updateChannel)

        store.updateChannel = AppUpdater.CHANNEL_GITHUB
        assertEquals(AppUpdater.CHANNEL_GITHUB, store.updateChannel)
    }
}
