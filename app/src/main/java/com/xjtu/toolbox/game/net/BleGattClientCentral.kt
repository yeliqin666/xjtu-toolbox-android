package com.xjtu.toolbox.game.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE 兜底通道，加入方(中心设备)侧：按服务 UUID 过滤扫描 → 连接 → 发现服务 → 协商 MTU →
 * 订阅 notify 特征值 → 就绪。结构上参照 Android 官方 BLE 指南 + `connectivity-samples`
 * 的 `BluetoothLeGatt` 示例（`DeviceScanActivity`/`BluetoothLeService` 那条"扫描→连接→
 * 发现服务→操作特征值"的主线），具体代码是照协程写法重写的，不是照抄。
 */
@SuppressLint("MissingPermission")
class BleGattClientCentral(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    /** 扫描 + 连接 + 订阅全流程；[serviceUuid] 来自二维码。整个过程受 [timeoutMs] 总控。 */
    suspend fun connect(serviceUuid: UUID, timeoutMs: Long = 10_000): OnlineTransport? =
        withTimeoutOrNull(timeoutMs) { doConnect(serviceUuid) }

    private suspend fun doConnect(serviceUuid: UUID): OnlineTransport? {
        val device = scanForDevice(serviceUuid) ?: return null
        return connectGatt(device, serviceUuid)
    }

    private suspend fun scanForDevice(serviceUuid: UUID): BluetoothDevice? {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice?>()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!found.isCompleted) found.complete(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                if (!found.isCompleted) found.complete(null)
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
        val device = try {
            found.await()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return device
    }

    private suspend fun connectGatt(device: BluetoothDevice, serviceUuid: UUID): OnlineTransport? {
        val readyDeferred = CompletableDeferred<Boolean>()
        val incomingChannel = Channel<String>(Channel.BUFFERED)
        val reassembler = BleReassembler()
        val writeLock = Mutex()
        var pendingWriteAck: CompletableDeferred<Boolean>? = null
        var writeChar: BluetoothGattCharacteristic? = null
        var negotiatedMtu = BleGattIds.DEFAULT_ATT_MTU
        var gattRef: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    gatt.requestMtu(BleGattIds.REQUESTED_MTU)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                    incomingChannel.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                negotiatedMtu = mtu
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(serviceUuid) ?: gatt.services?.firstOrNull()
                val notify = service?.getCharacteristic(BleGattIds.CHAR_TO_GUEST)
                val write = service?.getCharacteristic(BleGattIds.CHAR_TO_HOST)
                writeChar = write
                if (notify == null || write == null) {
                    if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                    return
                }
                gatt.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(BleGattIds.CCCD) ?: run {
                    if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == BleGattIds.CCCD && !readyDeferred.isCompleted) {
                    readyDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                pendingWriteAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != BleGattIds.CHAR_TO_GUEST) return
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                val full = reassembler.feed(value)
                if (full != null) incomingChannel.trySend(String(full, Charsets.UTF_8))
            }
        }

        // 显式指定 TRANSPORT_LE：不少机型在双模设备上不指定 transport 会走经典蓝牙的探测路径，
        // 多绕一圈甚至连不上，这里的外围设备只广播 BLE，没必要留这个歧义。
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gattRef = gatt
        val ok = readyDeferred.await()
        if (!ok) {
            runCatching { gatt.close() }
            return null
        }

        return object : OnlineTransport {
            override val channelLabel: String = "蓝牙"
            override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

            override suspend fun send(line: String) = writeLock.withLock {
                val char = writeChar ?: return@withLock
                val bytes = line.toByteArray(Charsets.UTF_8)
                val fragments = BleFraming.fragment(0, bytes, negotiatedMtu)
                for (fragment in fragments) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingWriteAck = ack
                    val ok2 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gattRef?.writeCharacteristic(
                            char, fragment, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = fragment
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gattRef?.writeCharacteristic(char) == true
                    }
                    if (!ok2) break
                    withTimeoutOrNull(2_000) { ack.await() }
                }
            }

            override fun close() {
                runCatching { gattRef?.disconnect() }
                runCatching { gattRef?.close() }
                incomingChannel.close()
            }
        }
    }
}
