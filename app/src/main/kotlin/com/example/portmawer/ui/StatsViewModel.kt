package com.example.portmawer.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.portmawer.PortMawerVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsViewModel : ViewModel() {

    private val _stats = MutableLiveData<ConnectionStats>()
    val stats: LiveData<ConnectionStats> = _stats

    private var isMonitoring = false

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        
        viewModelScope.launch {
            while (isMonitoring) {
                // In a real implementation, you'd use IPC to get stats from the service
                // For now, this is a placeholder
                delay(1000)
            }
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }
}

data class ConnectionStats(
    val packetsForwarded: Long = 0,
    val packetsIntercepted: Long = 0,
    val bytesForwarded: Long = 0,
    val activeConnections: Int = 0,
    val activePort: Int = -1,
    val uptime: Long = 0
)