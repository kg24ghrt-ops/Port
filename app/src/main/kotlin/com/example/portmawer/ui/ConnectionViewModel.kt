package com.example.portmawer.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.portmawer.PortData
import com.example.portmawer.PortSelectorWrapper
import com.example.portmawer.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionViewModel : ViewModel() {

    private val selector = PortSelectorWrapper()

    private val _connectionState = MutableLiveData(TunnelState.DISCONNECTED)
    val connectionState: LiveData<TunnelState> = _connectionState

    private val _activePort = MutableLiveData(-1)
    val activePort: LiveData<Int> = _activePort

    private val _portList = MutableLiveData<List<PortData>>(emptyList())
    val portList: LiveData<List<PortData>> = _portList

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _statusMessage = MutableLiveData("Ready to connect")
    val statusMessage: LiveData<String> = _statusMessage

    // Test against reliable CDN endpoints that actually listen on these ports
    private val targetHost = "cloudflare.com"
    
    // Only test ports that Cloudflare/CDNs actually use
    private val defaultPorts = listOf(
        443,    // Standard HTTPS
        8443,   // Alt HTTPS (some CDNs)
        2053,   // Cloudflare API
        2083,   // Cloudflare API
        2087,   // Cloudflare API
        2096    // Cloudflare API
    )

    init {
        selector.initialize(targetHost)
        initializePortList()
    }

    private fun initializePortList() {
        _portList.value = defaultPorts.map { port ->
            PortData(port = port, isActive = false, statusText = "Pending")
        }
    }

    fun connect() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)
            _connectionState.postValue(TunnelState.TESTING_PORTS)
            _statusMessage.postValue("Scanning network paths...")

            val results = defaultPorts.map { 
                PortData(port = it, isActive = false, statusText = "Testing...") 
            }.toMutableList()

            var bestPort: PortData? = null

            for (i in results.indices) {
                val port = results[i].port
                
                withContext(Dispatchers.Main) { 
                    _portList.value = results.toList() 
                }

                try {
                    val resultString = selector.testPortSync(targetHost, port)
                    val parts = resultString.split(":")
                    val isSuccess = parts[0] == "OK"
                    val latency = if (isSuccess && parts.size > 1) {
                        parts[1].toIntOrNull() ?: -1
                    } else -1
                    
                    val statusText = when {
                        isSuccess && latency >= 0 -> "✓ ${latency}ms"
                        isSuccess -> "✓ Connected"
                        else -> "✗ Unreachable"
                    }

                    results[i] = results[i].copy(
                        isActive = isSuccess,
                        latencyMs = latency,
                        statusText = statusText
                    )

                    withContext(Dispatchers.Main) { 
                        _portList.value = results.toList() 
                    }

                    if (isSuccess && (bestPort == null || latency < bestPort!!.latencyMs)) {
                        bestPort = results[i]
                    }
                } catch (e: Exception) {
                    results[i] = results[i].copy(
                        statusText = "✗ Error"
                    )
                    withContext(Dispatchers.Main) { 
                        _portList.value = results.toList() 
                    }
                }
            }

            _isScanning.postValue(false)

            if (bestPort != null) {
                _connectionState.postValue(TunnelState.CONNECTING)
                _statusMessage.postValue("Establishing secure tunnel via :${bestPort.port}...")
                
                selector.testPort(bestPort.port)
                val connected = selector.connect()

                if (connected) {
                    _activePort.postValue(bestPort.port)
                    _connectionState.postValue(TunnelState.CONNECTED)
                    _statusMessage.postValue("✓ Connected via :${bestPort.port} | Secure DNS active")

                    val finalResults = results.map { 
                        it.copy(isSelected = it.port == bestPort.port) 
                    }
                    withContext(Dispatchers.Main) { 
                        _portList.value = finalResults 
                    }
                } else {
                    _connectionState.postValue(TunnelState.ERROR)
                    _statusMessage.postValue("✗ Tunnel establishment failed")
                }
            } else {
                _connectionState.postValue(TunnelState.ERROR)
                _statusMessage.postValue("✗ No reachable paths found")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            selector.disconnect()
            _connectionState.postValue(TunnelState.DISCONNECTED)
            _activePort.postValue(-1)
            _statusMessage.postValue("Disconnected")
            withContext(Dispatchers.Main) {
                _portList.value = _portList.value?.map { 
                    it.copy(isSelected = false, statusText = "Pending") 
                }
            }
        }
    }

    fun selectPort(port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _statusMessage.postValue("Switching to :$port...")
            _connectionState.postValue(TunnelState.RECONNECTING)
            
            selector.testPort(port)
            _activePort.postValue(port)
            _connectionState.postValue(TunnelState.CONNECTED)
            _statusMessage.postValue("✓ Active on :$port")

            withContext(Dispatchers.Main) {
                _portList.value = _portList.value?.map { 
                    it.copy(isSelected = it.port == port) 
                }
            }
        }
    }

    fun refreshPorts() = connect()

    override fun onCleared() {
        super.onCleared()
        selector.shutdown()
    }
}