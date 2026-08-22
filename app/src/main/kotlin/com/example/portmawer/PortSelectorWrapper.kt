package com.example.portmawer

/**
 * JNI bridge to the C++ native library.
 * Handles all port testing, selection, and tunnel management.
 */
class PortSelectorWrapper {

    companion object {
        init {
            System.loadLibrary("portmawer")
        }
    }

    interface PortTestCallback {
        fun onPortTested(port: Int, success: Boolean)
    }

    // Native methods
    private external fun nativeInit(host: String)
    private external fun nativeShutdown()
    private external fun nativeConnect(): Boolean
    private external fun nativeDisconnect()
    private external fun nativeReconnect()
    private external fun nativeGetActivePort(): Int
    private external fun nativeGetState(): Int
    private external fun nativeTestPort(port: Int)
    private external fun nativeTestPortSync(host: String, port: Int): String
    private external fun nativeStartScan(callback: PortTestCallback)
    private external fun nativeStopScan()
    private external fun nativeGetStats(): String

    // Public API
    fun initialize(host: String) = nativeInit(host)
    fun shutdown() = nativeShutdown()
    fun connect(): Boolean = nativeConnect()
    fun disconnect() = nativeDisconnect()
    fun reconnect() = nativeReconnect()
    fun getActivePort(): Int = nativeGetActivePort()
    fun getState(): Int = nativeGetState()
    fun testPort(port: Int) = nativeTestPort(port)
    fun testPortSync(host: String, port: Int): String = nativeTestPortSync(host, port)
    fun startScan(callback: PortTestCallback) = nativeStartScan(callback)
    fun stopScan() = nativeStopScan()
    fun getStats(): String = nativeGetStats()
}