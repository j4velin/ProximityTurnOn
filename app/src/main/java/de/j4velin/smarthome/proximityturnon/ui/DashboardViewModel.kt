package de.j4velin.smarthome.proximityturnon.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.j4velin.smarthome.proximityturnon.LightSensorService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel : ViewModel() {

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _serviceInstance = MutableStateFlow<LightSensorService?>(null)

    val serviceState: StateFlow<LightSensorService.State> = _serviceInstance.flatMapLatest { service ->
        service?.state ?: flowOf(LightSensorService.State())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LightSensorService.State())

    fun setWakeOnShadow(enabled: Boolean) {
        _serviceInstance.value?.setWakeOnShadow(enabled)
    }

    fun setShadowDropPercent(percent: Int) {
        _serviceInstance.value?.setShadowDropPercent(percent)
    }

    fun setBeepOnShadow(enabled: Boolean) {
        _serviceInstance.value?.setBeepOnShadow(enabled)
    }

    fun setConfirmWithCamera(enabled: Boolean) {
        _serviceInstance.value?.setConfirmWithCamera(enabled)
    }

    fun testCameraCheck() {
        _serviceInstance.value?.testCameraCheck()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LightSensorService.LocalBinder
            _serviceInstance.value = binder.getService()
            _isServiceRunning.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _serviceInstance.value = null
            _isServiceRunning.value = false
        }
    }

    fun checkServiceStatus(context: Context) {
        _isServiceRunning.value = isServiceRunning(context)
        if (_isServiceRunning.value) {
            bindToService(context)
        }
    }

    fun toggleService(context: Context) {
        if (_isServiceRunning.value) {
            stopService(context)
        } else {
            startService(context)
        }
    }

    private fun startService(context: Context) {
        val intent = Intent(context, LightSensorService::class.java)
        context.startForegroundService(intent)
        bindToService(context)
        _isServiceRunning.value = true
    }

    private fun stopService(context: Context) {
        unbindFromService(context)
        val intent = Intent(context, LightSensorService::class.java)
        context.stopService(intent)
        _isServiceRunning.value = false
    }

    private fun bindToService(context: Context) {
        val intent = Intent(context, LightSensorService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService(context: Context) {
        if (_serviceInstance.value != null) {
            context.unbindService(serviceConnection)
            _serviceInstance.value = null
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (LightSensorService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun onResume(context: Context) {
        checkServiceStatus(context)
    }

    fun onPause(context: Context) {
        unbindFromService(context)
    }
}
