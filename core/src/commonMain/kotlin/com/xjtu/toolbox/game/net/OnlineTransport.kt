package com.xjtu.toolbox.game.net

import kotlinx.coroutines.flow.Flow

/**
 * 联机传输层的统一接口：局域网 TCP 和 BLE GATT 两种实现都对上层长这个样子——
 * 上层（[OnlineGameSession]）只管发一行协议文本、收一行协议文本，不关心底下是
 * 直连 socket 还是 GATT 分片重组出来的。
 *
 * 不在这个接口里放"连接中/已连接"之类的状态——那是每种传输自己建立连接的过程
 * （TCP 三次握手、BLE 扫描+连接+服务发现+MTU 协商…差异很大），[OnlineTransport]
 * 只代表"已经连通、能收发整行消息"这个状态之后的东西。连接过程由各自的 Connector/Host
 * 类暴露自己的状态，连上了才产出一个 [OnlineTransport] 交给上层。
 */
interface OnlineTransport {
    /** 完整的一行协议 JSON；BLE 实现已经在内部做完分片重组，这里吐出来的都是整条消息。 */
    val incoming: Flow<String>

    /** 发一行协议 JSON。BLE 实现内部会自己切片、串行排队写入。 */
    suspend fun send(line: String)

    /** 关闭底层连接，之后 [incoming] 应该正常结束（不再发新值），重复调用安全。 */
    fun close()

    /** 给 UI 显示"当前走的是哪条通道"，纯展示用。 */
    val channelLabel: String
}
