package com.xjtu.toolbox.webvpn

/** WebVPN 页「常用站点」里的一项：点了直接转成 WebVPN 地址打开。 */
internal data class WebVpnSite(val name: String, val url: String)

internal object WebVpnSites {
    /** 只在校园网内能开的校内站。 */
    val campus = listOf(
        WebVpnSite("图书馆", "https://www.lib.xjtu.edu.cn/"),
        WebVpnSite("网上办事大厅", "https://ehall.xjtu.edu.cn/"),
        WebVpnSite("考勤系统", "https://kq.xjtu.edu.cn/"),
        WebVpnSite("研究生院", "https://gs.xjtu.edu.cn/"),
    )

    /** 图书馆按校园网 IP 授权的常用数据库：走 WebVPN 才能免登录检索、下载全文。 */
    val academic = listOf(
        WebVpnSite("中国知网", "https://www.cnki.net/"),
        WebVpnSite("万方", "https://www.wanfangdata.com.cn/"),
        WebVpnSite("维普", "https://qikan.cqvip.com/"),
        WebVpnSite("Web of Science", "https://www.webofscience.com/"),
        WebVpnSite("IEEE Xplore", "https://ieeexplore.ieee.org/"),
        WebVpnSite("ScienceDirect", "https://www.sciencedirect.com/"),
        WebVpnSite("SpringerLink", "https://link.springer.com/"),
        WebVpnSite("Wiley", "https://onlinelibrary.wiley.com/"),
        WebVpnSite("ACM DL", "https://dl.acm.org/"),
        WebVpnSite("Nature", "https://www.nature.com/"),
    )
}
