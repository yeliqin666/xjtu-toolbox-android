package com.xjtu.toolbox.game.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 联机功能需要的运行时权限，只在用户进入"联机对战"这个入口时才申请——
 * 单机人机、同屏双人两种模式完全不碰这个文件，也就不会触发任何权限弹窗。
 *
 * minSdk 31（Android 12）意味着蓝牙权限只有新模型：`BLUETOOTH_SCAN` /
 * `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`，不用再考虑 Android 11 及更早那套
 * "蓝牙扫描等于定位权限"的旧模型。`BLUETOOTH_SCAN` 在 Manifest 里声明了
 * `android:usesPermissionFlags="neverForLocation"`（见 AndroidManifest.xml），
 * 向系统承诺"扫描结果不用来推断位置"，这样才不用连带申请定位权限。
 */
object OnlinePermissions {

    val BLE_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    fun hasAllBlePermissions(context: Context): Boolean =
        BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
