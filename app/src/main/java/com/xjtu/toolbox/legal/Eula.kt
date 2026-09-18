package com.xjtu.toolbox.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用户协议与隐私政策的**唯一正文**。
 *
 * 以前这份内容有两份：首次启动那一页是完整的八条，设置里的「用户协议与隐私政策」
 * 是另外手写的两段摘要。两边措辞、范围都不一样——用户在设置里读到的并不是他当初
 * 同意的那份，改一处漏一处。现在两个入口都渲染这里。
 *
 * 改条款时记得同步递增 `CredentialStore.EULA_VERSION`，否则老用户不会被要求重新同意。
 */
object Eula {

    data class Section(val title: String, val body: AnnotatedString)

    @Composable
    fun sections(): List<Section> {
        val boldStyle = SpanStyle(
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
        )
        return listOf(
            Section(
                "一、应用性质",
                buildAnnotatedString {
                    append("本应用（「岱宗盒子」）是西安交通大学学生自主开发的非官方校园工具，")
                    pushStyle(boldStyle); append("完全开源、无毒无害"); pop()
                    append("，通过模拟浏览器行为访问学校现有的 Web 服务接口，为学生提供统一便捷的校园信息查询体验。本应用不隶属于、不代表西安交通大学或其任何部门。")
                }
            ),
            Section(
                "二、数据来源与使用",
                buildAnnotatedString {
                    append("本应用通过 HTTPS 协议访问学校各业务系统接口获取数据，校园系统请求均在您的设备上发起。您的账号凭据（用户名和密码）仅加密存储在本地设备中，不会上传至开发者服务器。")
                    append("应用闪退时，会在下次启动后匿名上报崩溃日志（异常堆栈、应用版本、机型与系统版本，其中的网址参数、长串数字和令牌已预先脱敏），仅用于定位和修复问题，可在「设置 → 关于 → 自动上报崩溃日志」中关闭。")
                    pushStyle(boldStyle); append("请勿将账号、验证码、API Key 等敏感信息交给不可信来源。"); pop()
                }
            ),
            Section(
                "三、AI 与第三方服务",
                buildAnnotatedString {
                    append("屁岱等 AI 功能由用户自行配置模型服务与 API Key。使用这些功能时，您的问题、上下文、工具查询结果、上传附件摘要等内容可能会发送给您选择的模型服务商或中转服务。")
                    pushStyle(boldStyle); append("请优先选择可信服务商，妥善保管 API Key，避免提交不希望第三方处理的个人信息。"); pop()
                }
            ),
            Section(
                "四、本地文件与下载",
                buildAnnotatedString {
                    append("成绩单、课件、作业附件等下载内容会按系统规则保存到本机下载目录或应用私有目录。保存到公共下载目录的文件可能被文件管理器、备份软件或具备相应权限的其他应用读取。请自行管理、删除或转移包含个人信息的文件。")
                }
            ),
            Section(
                "五、免责声明",
                buildAnnotatedString {
                    append("1. 本应用按「按原样」（AS IS）提供，开发者不对其准确性、完整性、可用性或适用性作任何明示或暗示的保证。\n2. 因使用本应用导致的任何直接或间接损失（包括但不限于数据丢失、账号异常、学业影响等），开发者不承担任何责任。\n3. 若学校系统接口变更导致功能异常，开发者将尽力修复但不保证时效。\n4. 本应用可能因学校政策调整而需要停止服务，届时将提前告知用户。")
                }
            ),
            Section(
                "六、合规声明",
                buildAnnotatedString {
                    append("1. 本应用仅供西安交通大学在校师生个人学习和生活使用，严禁用于任何商业用途。\n2. ")
                    pushStyle(boldStyle); append("本应用不提供抢选、抢课、刷分等牟利功能。"); pop()
                    append("\n3. ")
                    pushStyle(boldStyle); append("本应用不接入支付、退款等金额交易功能。"); pop()
                    append("\n4. 使用者应遵守学校各系统的使用规定和信息安全管理条例。\n5. 本应用会尽量复用会话并限制异常重试，但严禁利用本应用进行恶意请求、批量爬取、接口滥用等行为。违者应自行承担相应责任。")
                }
            ),
            Section(
                "七、知识产权",
                buildAnnotatedString {
                    append("本应用源代码基于 MIT 协议开源，感谢相关项目的启发。所访问的各业务系统之数据、接口及商标均归西安交通大学及相关权利方所有。")
                }
            ),
            Section(
                "八、条款变更",
                buildAnnotatedString {
                    append("开发者保留随时修改本协议的权利。更新后的协议将在新版本发布时生效，继续使用本应用即视为接受修改后的条款。")
                }
            )
        )
    }

    /** 条款正文卡片。首启页和设置页共用，保证两处一字不差。 */
    @Composable
    fun Body(modifier: Modifier = Modifier) {
        val items = sections()
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                items.forEachIndexed { idx, section ->
                    if (idx > 0) Spacer(Modifier.height(12.dp))
                    Text(
                        section.title,
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        section.body,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}
