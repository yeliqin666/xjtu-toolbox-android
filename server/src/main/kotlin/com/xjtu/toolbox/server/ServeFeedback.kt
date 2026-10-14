package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi

/**
 * 把**反馈后端的凭据**交给 `:data` 的 [FeedbackApi]（serve 端那一份）。
 *
 * ## 为什么服务端要自己做这件事
 *
 * 反馈那条链的「登录」不是校园站点会话，而是拿 `app_id` + `app_secret` 换一张
 * `tenant_access_token`（见 `FeedbackApi` 的 KDoc）；那两个凭据由**宿主**在启动时注入。
 * 两个窗口端各自的注入点是 `:app` 的 `AppFeedback.install()` 与桌面的 `DesktopFeedback.install()`，
 * 两处的值都来自**构建期**烘焙的常量（`BuildConfig.FEEDBACK_*` / `DesktopBuildConfig.FEEDBACK_*`）。
 *
 * serve 这一端不能照抄「构建期烘焙」：它是**部署在那台机器上**的常驻进程（`--dist` 可以指到任何
 * 目录、换一份产物不用重编），凭据按运行期注入才是它这一形态的正确答案。所以这里是本端独有的
 * **第三处注入点**（前两处都在窗口端的构建脚本里），读的是同一批环境变量名：
 *
 * | 环境变量 | 对应用途 |
 * |---|---|
 * | `FEEDBACK_APP_ID` | 应用凭据（换租户令牌） |
 * | `FEEDBACK_APP_SECRET` | 同上 |
 * | `FEEDBACK_BASE_TOKEN` | 多维表格的 app token |
 * | `FEEDBACK_TABLE_SUBMIT` | 提交表（只给**写**权限；serve 这一轮不端提交，留着只为把 Config 填满） |
 * | `FEEDBACK_TABLE_REPLY` | 回复表（只给**读**权限；`/api/feedback*` 两条都读写它） |
 *
 * 名字与 `desktop/build.gradle.kts` 里那段 `cfg()` 推导出来的**逐字相同**
 * （`appId` → `FEEDBACK_APP_ID`，依此类推）——操作者只有一套名字要记。
 *
 * ## 没配凭据时：如实「没配」，不假装有
 *
 * 三个必填（`app_id` / `app_secret` / `base_token`）里有任何一个空 ⇒ `config` 置 `null` ⇒
 * `FeedbackApi.isConfigured` 为 false ⇒ `/api/feedback*` 按 502 + 一句「去哪儿配」的中文短句报
 *（见 [FEEDBACK_UNCONFIGURED_MESSAGE]）。这与两个窗口端同一条口径：别人 clone 下来照样能跑，
 * 反馈那一块整块退化成外链 —— 服务端**不**因为「是服务端」就特殊照顾，也**不**拿一份假凭据顶上。
 *
 * ## 那三个展示字段（版本 / 机型 / 系统）
 *
 * 它们在 [FeedbackApi.Config] 里是给**提交**那一列用的（`:data` 写进多维表格的「版本 / 机型 /
 * 系统」三列）。serve 这一轮**没有**提交端点（写路径不端），所以这三格不会被发出去；但 Config
 * 的形状要求它们非空可读，所以这里照 `:desktop` 的做法**如实报这台机器**（不假装是手机）：
 * 版本写 `serve`、机型写 `os.name + os.arch`、系统写 `os.name + os.version + Java 版本`。
 *
 * @param env 读凭据的那张环境变量表。默认 [System.getenv]；测试传一份自己的（不碰进程环境）。
 */
internal object ServeFeedback {

    /** 幂等：在 [Main] 里启动前调一次。 */
    fun install(env: Map<String, String> = System.getenv()) {
        fun cfg(name: String): String = env[name].orEmpty().trim()

        val appId = cfg(ENV_APP_ID)
        val appSecret = cfg(ENV_APP_SECRET)
        val baseToken = cfg(ENV_BASE_TOKEN)
        FeedbackApi.config = if (appId.isEmpty() || appSecret.isEmpty() || baseToken.isEmpty()) {
            // 三个必填缺一个 ⇒ 整条链不可用（`FeedbackApi.isConfigured` 的判据就是这三个非空）
            null
        } else {
            FeedbackApi.Config(
                appId = appId,
                appSecret = appSecret,
                baseToken = baseToken,
                submitTable = cfg(ENV_TABLE_SUBMIT),
                replyTable = cfg(ENV_TABLE_REPLY),
                versionLabel = VERSION_LABEL,
                deviceLabel = "${osName()} ${System.getProperty("os.arch").orEmpty()}".trim(),
                osLabel = "${osName()} ${System.getProperty("os.version").orEmpty()}" +
                    " / Java ${System.getProperty("java.version").orEmpty()}",
            )
        }
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty()

    /** 「版本」列的值：serve 没有自己的版本号（它就是这台机器上这份产物），如实写这一端的名字。 */
    private const val VERSION_LABEL = "serve"

    internal const val ENV_APP_ID = "FEEDBACK_APP_ID"
    internal const val ENV_APP_SECRET = "FEEDBACK_APP_SECRET"
    internal const val ENV_BASE_TOKEN = "FEEDBACK_BASE_TOKEN"
    internal const val ENV_TABLE_SUBMIT = "FEEDBACK_TABLE_SUBMIT"
    internal const val ENV_TABLE_REPLY = "FEEDBACK_TABLE_REPLY"
}
