package com.xjtu.toolbox.server

import kotlinx.serialization.Serializable

/** `GET /api/status` 的响应体 —— 裸对象（不走 [ApiEnvelope] 信封）。 */
@Serializable
data class ServeStatus(
    val authenticated: Boolean,
    val uptimeSeconds: Long,
)
