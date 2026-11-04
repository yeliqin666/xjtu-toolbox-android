package com.xjtu.toolbox.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 屁岱 `my_location` 的定位：按需申请权限、定位一次。用系统 [LocationManager]，不依赖 Play 服务；
 * 坐标是 WGS-84，与打包的 OpenStreetMap 校区数据同一套，不用纠偏。
 */
object AgentLocation {

    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private var settingsLauncher: ActivityResultLauncher<Intent>? = null
    private var settingsPending: CompletableDeferred<Unit>? = null

    /** 在 Activity.onCreate 里登记权限弹窗和定位设置页（必须在 STARTED 之前）。 */
    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pending?.complete(result.values.any { it })
            pending = null
        }
        settingsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            settingsPending?.complete(Unit)
            settingsPending = null
        }
    }

    private fun enabledProviders(lm: LocationManager) =
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    /** 定位总开关关着时跳系统定位设置，等用户返回。 */
    private suspend fun ensureEnabled(context: Context, lm: LocationManager): Boolean {
        if (lm.isLocationEnabled) return true
        val l = settingsLauncher ?: return false
        val wait = CompletableDeferred<Unit>().also { settingsPending = it }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "请打开定位后返回", Toast.LENGTH_SHORT).show()
            l.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        wait.await()
        return lm.isLocationEnabled
    }

    private fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun ensurePermission(context: Context): Boolean {
        if (granted(context)) return true
        val l = launcher ?: return false
        val wait = CompletableDeferred<Boolean>().also { pending = it }
        withContext(Dispatchers.Main) {
            l.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        return wait.await()
    }

    /** 当前位置 (纬度, 经度)；没授权、定位关了、15 秒内没定到返回 null。两分钟内的上次位置直接用。 */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun current(context: Context): Pair<Double, Double>? {
        if (!ensurePermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!ensureEnabled(context, lm)) return null
        val providers = enabledProviders(lm)
        val now = System.currentTimeMillis()
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < 2 * 60_000 }
            .minByOrNull { it.accuracy }
            ?.let { return it.latitude to it.longitude }
        val provider = providers.firstOrNull() ?: return null
        val fix = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }
        }
        return fix?.let { it.latitude to it.longitude }
    }
}
