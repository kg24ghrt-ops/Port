package com.example.portmawer

/**
 * Data class representing a port's test result for UI display.
 */
data class PortData(
    val port: Int,
    val isActive: Boolean,
    val latencyMs: Int = -1,
    val isSelected: Boolean = false,
    val statusText: String = if (isActive) "OK" else "FAILED"
)

/**
 * Tunnel connection states matching the C++ enum.
 */
enum class TunnelState(val value: Int) {
    DISCONNECTED(0),
    TESTING_PORTS(1),
    CONNECTING(2),
    CONNECTED(3),
    RECONNECTING(4),
    ERROR(5);

    companion object {
        fun fromInt(value: Int): TunnelState {
            return entries.find { it.value == value } ?: DISCONNECTED
        }
    }

    val displayText: String
        get() = when (this) {
            DISCONNECTED -> "Disconnected"
            TESTING_PORTS -> "Testing Ports..."
            CONNECTING -> "Connecting..."
            CONNECTED -> "Connected"
            RECONNECTING -> "Reconnecting..."
            ERROR -> "Error"
        }
}