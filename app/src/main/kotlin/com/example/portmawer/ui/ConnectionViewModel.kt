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

    private val _statusMessage = MutableLiveData("Ready")
    val statusMessage: LiveData<String> = _statusMessage

    private val targetHost = "one.one.one.one" // Test target

    // Default ports to scan
    private val defaultPorts = listOf(
        443, 8443, 4443, 2053, 2083, 2087,
        2096, 8080, 8888, 9443, 44321,
        10443, 20443, 30443
    )

    init {
        selector.initialize(targetHost)
        initializePortList()
    }

    private fun initializePortList() {
        _portList.value = defaultPorts.map { port ->
            PortData(port = port, isActive = false)
        }
    }

    fun connect() {
        viewModelScope.launch(Dispatchers.IO) {
            _connectionState.postValue(TunnelState.TESTING_PORTS)
            _statusMessage.postValue("Testing available ports...")

            // Quick test on default ports
            val results = mutableListOf<PortData>()
            for (port in defaultPorts) {
                val result = selector.testPortSync(targetHost, port)
                val parts = result.split(":")
                val success = parts[0] == "OK"
                val latency = if (success && parts.size > 1) parts[1].toIntOrNull() ?: -1 else -1

                results.add(
                    PortData(
                        port = port,
                        isActive = success,
                        latencyMs = latency,
                        statusText = if (success) "OK ${latency}ms" else "FAILED"
                    )
                )

                withContext(Dispatchers.Main) {
                    _portList.value = results + defaultPorts.drop(results.size).map {
                        PortData(port = it, isActive = false)
                    }
                }
            }

            // Find best port
            val bestPort = results
                .filter { it.isActive }
                .minByOrNull { it.latencyMs }

            if (bestPort != null) {
                _connectionState.postValue(TunnelState.CONNECTING)
                _statusMessage.postValue("Connecting via port ${bestPort.port}...")

                selector.testPort(bestPort.port)
                val connected = selector.connect()

                if (connected) {
                    _connectionState.postValue(TunnelState.CONNECTED)
                    _activePort.postValue(bestPort.port)
                    _statusMessage.postValue("Connected via port ${bestPort.port}")

                    // Update UI to show selected port
                    withContext(Dispatchers.Main) {
                        _portList.value = results.map {
                            it.copy(isSelected = it.port == bestPort.port)
                        }
                    }
                } else {
                    _connectionState.postValue(TunnelState.ERROR)
                    _statusMessage.postValue("Connection failed")
                }
            } else {
                _connectionState.postValue(TunnelState.ERROR)
                _statusMessage.postValue("No available ports found")
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
                _portList.value = _portList.value?.map { it.copy(isSelected = false) }
            }
        }
    }

    fun selectPort(port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _statusMessage.postValue("Testing port $port...")
            val result = selector.testPortSync(targetHost, port)
            val success = result.startsWith("OK")

            if (success) {
                selector.testPort(port)
                _activePort.postValue(port)
                _statusMessage.postValue("Switched to port $port")

                withContext(Dispatchers.Main) {
                    _portList.value = _portList.value?.map {
                        it.copy(isSelected = it.port == port)
                    }
                }
            } else {
                _statusMessage.postValue("Port $port is not responding")
            }
        }
    }

    fun refreshPorts() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)
            _statusMessage.postValue("Scanning ports...")

            val results = mutableListOf<PortData>()
            for (port in defaultPorts) {
                val result = selector.testPortSync(targetHost, port)
                val parts = result.split(":")
                val success = parts[0] == "OK"
                val latency = if (success && parts.size > 1) parts[1].toIntOrNull() ?: -1 else -1

                results.add(
                    PortData(
                        port = port,
                        isActive = success,
                        latencyMs = latency,
                        isSelected = port == _activePort.value,
                        statusText = if (success) "OK ${latency}ms" else "FAILED"
                    )
                )
            }

            withContext(Dispatchers.Main) {
                _portList.value = results
                _isScanning.postValue(false)
                _statusMessage.postValue("Scan complete")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        selector.shutdown()
    }
}