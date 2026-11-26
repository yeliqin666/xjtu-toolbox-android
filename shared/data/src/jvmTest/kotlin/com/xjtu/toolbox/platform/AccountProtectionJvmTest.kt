package com.xjtu.toolbox.platform

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountStore
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountProtectionJvmTest {
    @Test
    fun windowsAccountTableMigratesAndProtectsNewPasswords() {
        if (secretProtectorForPlatform() == null) return
        val previous = dataRootOverride
        val root = Files.createTempDirectory("account-protection-test").toFile()
        dataRootOverride = root
        try {
            wipeSecureStore(AccountStore.FILE_NAME)
            val file = File(root, "${AccountStore.FILE_NAME}.properties")
            Properties().apply {
                setProperty("accounts", """[{"accountId":"test","password":"legacy-test-password"}]""")
                setProperty("active_account", "test")
            }.let { props -> file.outputStream().use { props.store(it, null) } }

            val accounts = AccountStore()
            assertEquals("legacy-test-password", accounts.activeAccount()?.password)
            fun rowsOnDisk(): String = Properties().apply {
                file.inputStream().use { load(it) }
            }.getProperty("accounts")
            assertTrue(rowsOnDisk().startsWith("xjtu-sealed:1:dpapi:"))
            assertFalse(file.readText().contains("legacy-test-password"))

            accounts.upsert(Account(accountId = "second", password = "new-test-password"))
            assertTrue(rowsOnDisk().startsWith("xjtu-sealed:1:dpapi:"))
            assertFalse(file.readText().contains("new-test-password"))
            assertEquals("new-test-password", AccountStore().get("second")?.password)
            assertEquals("legacy-test-password", AccountStore().get("test")?.password)
        } finally {
            wipeSecureStore(AccountStore.FILE_NAME)
            dataRootOverride = previous
            root.deleteRecursively()
        }
    }
}
