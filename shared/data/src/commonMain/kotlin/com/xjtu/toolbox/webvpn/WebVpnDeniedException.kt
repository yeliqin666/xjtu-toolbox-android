package com.xjtu.toolbox.webvpn

import com.xjtu.toolbox.error.UserFacingFailure
import java.io.IOException

/**
 * [WebVpnInterceptor] 认出网关拒绝页时抛（见 [WebVpnDenied]）。消息是给用户看的整句，
 * 实现 [UserFacingFailure]，FriendlyError 原样给出，所有走 WebVPN 的站点同一句提示。
 */
class WebVpnDeniedException : IOException(MESSAGE), UserFacingFailure {
    companion object {
        const val MESSAGE = WebVpnDenied.MESSAGE
    }
}