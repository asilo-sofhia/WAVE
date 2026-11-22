package com.example.wave.data.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.wave.data.LocationPermissionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The real runtime permission. Asking needs an Activity, so MainActivity [attach]es its permission launcher
 * and reports back through [onResult]. [refresh] re-reads the permission (e.g. after the user returns from
 * system settings).
 */
class AndroidLocationPermissionGate(context: Context) : LocationPermissionGate {
    private val app = context.applicationContext

    private val _granted = MutableStateFlow(hasPermission())
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()

    @Volatile private var launcher: ((Array<String>) -> Unit)? = null
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    fun attach(launch: (Array<String>) -> Unit) {
        launcher = launch
    }

    fun detach(launch: (Array<String>) -> Unit) {
        if (launcher === launch) launcher = null
    }

    fun onResult() {
        refresh()
        pending?.complete(_granted.value)
        pending = null
    }

    fun refresh() {
        _granted.value = hasPermission()
    }

    override suspend fun request(): Boolean {
        if (hasPermission()) return true.also { refresh() }
        val launch = launcher ?: return false
        val result = CompletableDeferred<Boolean>().also { pending = it }
        withContext(Dispatchers.Main) { launch(PERMISSIONS) }
        return result.await()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Android 12+ needs COARSE asked together with FINE. Android 13+ also has NEARBY_WIFI_DEVICES. */
        val PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
}
