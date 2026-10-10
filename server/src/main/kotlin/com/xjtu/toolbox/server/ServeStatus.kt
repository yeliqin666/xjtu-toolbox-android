package com.xjtu.toolbox.server

import kotlinx.serialization.Serializable

/**
 * `GET /api/status` 的响应体 —— **裸对象**（不走 [ApiEnvelope] 信封）。
 *
 * 契约里唯一的两处例外之一（§4：「`/api/status` 是裸对象（历史遗留）」）：`campus-api` 当年就是
 * 这么发的，`:core` 的 `CampusApi.SessionStatus` 与 `:web` 的探针页也都按裸对象读。
 * serve 模式下这一条**沿用**（§5 的端点清单写着「沿用（但见 §3.4：不含身份）」）。
 *
 * ## 红线：这里不许出现身份信息
 *
 * §3.4：`/api/status` 是**唯一免令牌**端点，且**不含身份信息**（不返回学号 / 姓名）。
 * 原因很直接：它是免令牌的 —— 任何连上端口的进程都能读到它的每一个字段。
 * 所以「谁的会话」这件事只能由**带了令牌**的端点回答（那是 `/api/session` 的活）。
 *
 * ⚠️ 别照着 `:core` 的 `CampusApi.SessionStatus` 抄字段：那份里有 `username`（campus-api 是
 * 单账号回环、零鉴权的形态），在这里是**越界**的。这个类的字段集合就是 §3.4 的实现。
 *
 * @param authenticated 有没有一个可用的会话。会话内核在 `:data` 里，接线是**下一步**
 *   （`/api/session*`）的事 —— 在那之前这里如实报 `false`，不编。
 * @param uptimeSeconds serve 进程已运行的秒数（`campus-api` 同名字段的语义）。
 *   是个**时长**不是时刻，所以不是 §4 那条「时间一律 ISO-8601」的对象。
 */
@Serializable
data class ServeStatus(
    val authenticated: Boolean,
    val uptimeSeconds: Long,
)
