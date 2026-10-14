package com.xjtu.toolbox.account

/**
 * 多账号页那一族的**夹具常量**（`docs/api-contract.md` §5.10，工单 S7a / D9）。
 *
 * 这一族与其余族有一点不同：它**不打上游**（`/api/accounts` 读的是本机账号表 `xjtu_accounts`
 * 与 serve 自己的登录态）⇒ 夹具不是一份假上游的页面原文，而是**两个编造的账号**：
 * `:server:test` 把它们塞进 `AccountStore`，再断言响应里只有**打码**形式 —— 红线断言因此才成立
 * （「夹具里明明有完整学号/密码/姓名，响应里一个都不许有」，不是「夹具没有所以没出现」）。
 *
 * 与 `:testkit` 其余夹具同一条纪律：**只有 JDK 自己的东西、不依赖任何工程模块**
 * （所以这里是纯字符串常量，不是 `Account` 对象 —— `Account` 在 `:core`，而 `:testkit` 一个
 * 工程依赖都没有，`testkit/build.gradle.kts` 的 KDoc 记着这条）。
 */
object AccountsFixture {

    /** 主账号（10 位，够长 ⇒ 打码时留前 4 后 4）。 */
    const val STUDENT_NO = "2023000001"

    /** 第二个账号：用来验「表里不止一条」与 `active` 只标在 serve 当前会话那一条上。 */
    const val SECOND_STUDENT_NO = "2023000002"

    /** 主账号的密码。**响应里绝不许出现它**（连键都不该有）。 */
    const val PASSWORD = "fixture-account-password"

    /** 第二个账号的密码（同上）。 */
    const val SECOND_PASSWORD = "fixture-account-password-2"

    /** 姓名（`Account.nickname`）。**响应里绝不许出现它**（红线 §3.4）。 */
    const val NICKNAME = "夹具同学"

    /** 设备指纹（`Account.fpVisitorId`）：与密码同一条红线。 */
    const val FP_VISITOR_ID = "fixture-fp-visitor-id"

    /** 缓存的 RSA 公钥：同上。 */
    const val RSA_PUBLIC_KEY = "fixture-rsa-public-key"

    /** 主账号的打码形式（= `/api/accounts` 的 `id`，也是屏上的显示名）。 */
    const val MASKED_STUDENT_NO = "2023****0001"

    /** 第二个账号的打码形式。 */
    const val MASKED_SECOND_STUDENT_NO = "2023****0002"

    /** 账号表里那一条的「上次使用」时间（epoch 毫秒）：取一个固定的过去时刻，好断言 ISO 往返。 */
    const val LAST_USED_AT_MS = 1_700_000_000_000L
}
