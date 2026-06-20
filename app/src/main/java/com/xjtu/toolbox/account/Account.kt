package com.xjtu.toolbox.account

import com.xjtu.toolbox.auth.AccountType

/**
 * 一个已记录的校园账号。`accountId` 即学号/手机号，全局唯一且稳定，
 * 作为该账号所有命名空间存储的根键。
 *
 * 密码与设备指纹等敏感字段以明文内存形态持有，落盘由 [AccountStore] 用
 * EncryptedSharedPreferences 加密；不在此处自行处理加解密。
 */
data class Account(
    val accountId: String,
    val password: String,
    val accountType: AccountType = AccountType.UNDERGRADUATE,
    /** YWTB 全名缓存，欢迎卡片秒显示。 */
    val nickname: String? = null,
    /** CAS 设备信任指纹；按账号独立以避免账号间误用触发 MFA。 */
    val fpVisitorId: String? = null,
    /** RSA 公钥缓存（CAS 登录用，24h 有效）。 */
    val rsaPublicKey: String? = null,
    val rsaKeyTime: Long = 0L,
    /** 上次切换到此账号的时间，用于账号管理页排序。 */
    val lastUsedAt: Long = 0L,
)
