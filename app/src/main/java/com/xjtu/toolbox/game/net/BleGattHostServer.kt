package com.xjtu.toolbox.game.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * BLE 兜底通道，房主(外围设备)侧：广播 + GATT Server。
 *
 * 参考了 Android 官方 BLE 指南与 `android/connectivity-samples` 里几个 BLE 示例的整体结构
 * （广播 → GATT Server 起服务 → 处理特征值读写/描述符写/MTU 协商这条主线），但**没有照抄
 * 任何一份示例代码**——那些示例要么是纯中心设备（没有 GATT Server 部分），要么是 Java、
 * 状态管理方式跟这里基于协程的写法差别很大，所以这是按平台文档 + 蓝牙规范自己写的实现，
 * 不在 THIRD_PARTY_NOTICES.md 里单列条目。
 *
 * 单连接假设：联机对局是一对一的，这里只保留"当前唯一一个已连接设备"，第二个设备连上来
 * 会被直接拒绝（断开），不支持多人围观。
 */
@SuppressLint("MissingPermission") // 调用方必须先确认 OnlinePermissions.hasAllBlePermissions() 为 true
class BleGattHostServer(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var connectedDevice: BluetoothDevice? = null
    private var negotiatedMtu: Int = BleGattIds.DEFAULT_ATT_MTU

    private val reassembler = BleReassembler()
    private val incomingChannel = Channel<String>(Channel.BUFFERED)
    private var nextOutMsgId = 0
    private val writeLock = Mutex() // GATT 一次只能有一个未完成的写/通知操作在途，必须串行
    private var pendingNotifyAck: CompletableDeferred<Boolean>? = null

    private val readyDeferred = CompletableDeferred<Unit>()

    /** 开始广播 + 起 GATT Server；[serviceUuid] 是本局随机生成的服务 UUID（写进二维码那个）。 */
    fun start(serviceUuid: UUID) {
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeChar = BluetoothGattCharacteristic(
            BleGattIds.CHAR_TO_HOST,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            BleGattIds.CHAR_TO_GUEST,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            BleGattIds.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        notify.addDescriptor(cccd)
        service.addCharacteristic(writeChar)
        service.addCharacteristic(notify)
        notifyChar = notify

        val server = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer = server
        server?.addService(service)

        val adv = bluetoothManager.adapter?.bluetoothLeAdvertiser
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // 设备名可能带个人信息，联机码只需要服务 UUID 能匹配上
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        adv?.startAdvertising(settings, data, advertiseCallback)
    }

    /** 等待第一个加入方连上、完成 MTU 协商与订阅通知；超时返回 null。 */
    suspend fun waitForReady(): OnlineTransport? = try {
        kotlinx.coroutines.withTimeoutOrNull(READY_TIMEOUT_MS) { readyDeferred.await() }
        if (connectedDevice != null) HostTransport() else null
    } catch (_: Exception) {
        null
    }

    fun stop() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { connectedDevice?.let { gattServer?.cancelConnection(it) } }
        runCatching { gattServer?.close() }
        gattServer = null
        advertiser = null
        connectedDevice = null
        incomingChannel.close()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            readyDeferred.completeExceptionally(IllegalStateException("BLE 广播启动失败：$errorCode"))
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                if (connectedDevice != null && connectedDevice?.address != device.address) {
                    // 已经有一个人在下了，第二个连接直接拒绝——同屏一对一，不接受第三方围观。
                    runCatching { gattServer?.cancelConnection(device) }
                    return
                }
                connectedDevice = device
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                    incomingChannel.close()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BleGattIds.CHAR_TO_HOST) {
                val full = reassembler.feed(value)
                if (full != null) {
                    incomingChannel.trySend(String(full, Charsets.UTF_8))
                }
                if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            // 加入方写 CCCD 订阅通知——一旦订阅上，就算"连接就绪"（不用等它先发第一条业务消息）。
            if (descriptor.uuid == BleGattIds.CCCD && connectedDevice?.address == device.address) {
                if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotifyAck?.complete(status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** 暴露给上层协议状态机使用的 [OnlineTransport]，内部把分片/串行化都封装掉。 */
    private inner class HostTransport : OnlineTransport {
        override val channelLabel: String = "蓝牙"

        override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

        override suspend fun send(line: String) = writeLock.withLock {
            val device = connectedDevice ?: return@withLock
            val char = notifyChar ?: return@withLock
            val bytes = line.toByteArray(Charsets.UTF_8)
            val msgId = nextOutMsgId
            nextOutMsgId = (nextOutMsgId + 1) and 0xFF
            val fragments = BleFraming.fragment(msgId, bytes, negotiatedMtu)
            for (fragment in fragments) {
                val ack = CompletableDeferred<Boolean>()
                pendingNotifyAck = ack
                val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServer?.notifyCharacteristicChanged(device, char, false, fragment) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.value = fragment
                    @Suppress("DEPRECATION")
                    gattServer?.notifyCharacteristicChanged(device, char, false) == true
                }
                if (!sent) break
                // 等这一片真正发出去（onNotificationSent）再发下一片：GATT 同一时刻只能有一个
                // 未完成的操作，不排队会互相踩踏、丢片。
                kotlinx.coroutines.withTimeoutOrNull(2_000) { ack.await() }
            }
        }

        override fun close() = this@BleGattHostServer.stop()
    }

    companion object {
        private const val READY_TIMEOUT_MS = 10_000L
    }
}
