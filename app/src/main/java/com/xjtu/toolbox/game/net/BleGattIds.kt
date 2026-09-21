package com.xjtu.toolbox.game.net

import java.util.UUID

/**
 * BLE GATT 用到的固定 UUID：服务 UUID 是每局随机生成的（见 [OnlineQrPayload]），
 * 但服务里的两个特征值、标准 CCCD 描述符是协议本身的一部分，双方写死同一份常量。
 *
 * 两个特征值而不是一个：GATT 里"写"和"通知"是两个方向的操作，客户端(加入方)只能
 * "写"外围设备(房主)的特征值，外围设备只能"通知"已订阅的客户端——不存在一个双向
 * 读写的特征值，所以拆成 host 收（WRITE）、host 发（NOTIFY）两个。
 */
object BleGattIds {
    /** 加入方写、房主收——协议消息从加入方发往房主走这个特征值。 */
    val CHAR_TO_HOST: UUID = UUID.fromString("6e6f7401-0000-1000-8000-00805f9b34fb")

    /** 房主通过 notify 推、加入方订阅收——协议消息从房主发往加入方走这个特征值。 */
    val CHAR_TO_GUEST: UUID = UUID.fromString("6e6f7402-0000-1000-8000-00805f9b34fb")

    /** 标准 Client Characteristic Configuration Descriptor，开关 notify 用，蓝牙规范里的固定值。 */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** GATT 默认（未协商）MTU 是 23 字节，去掉 3 字节 ATT 头只剩 20 字节可用净荷。 */
    const val DEFAULT_ATT_MTU = 23

    /** 协商时申请的 MTU；大多数机型能给到这个数附近，拿不到就退回协商结果实际值。 */
    const val REQUESTED_MTU = 247
}
