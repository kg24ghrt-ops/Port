package com.example.portmawer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class PortMawerVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "portmawer_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.example.portmawer.CONNECT"
        const val ACTION_DISCONNECT = "com.example.portmawer.DISCONNECT"
    }

    private var tunnelInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val selector = PortSelectorWrapper()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        selector.initialize("cloudflare.com") // Target for dynamic port selection
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startVpn()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("PortMawer Local Forwarder")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(true)
            .addDisallowedApplication(packageName) // Prevent loops

        tunnelInterface = builder.establish() ?: return stopSelf()

        startForeground(NOTIFICATION_ID, createNotification())

        // Start the packet interception and forwarding engine
        serviceScope.launch {
            forwardPackets()
        }
    }

    /**
     * The Core Engine: Reads raw IP packets from Android, 
     * intercepts Port 443, and fires them out a new local outbound socket.
     */
    private suspend fun forwardPackets() {
        val inputStream = FileInputStream(tunnelInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(tunnelInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isActive) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                // 1. Parse the IP Header to find the destination and protocol
                val version = (buffer[0].toInt() shr 4) and 0x0F
                if (version != 4) continue // Only handle IPv4 for now

                val ihl = (buffer[0].toInt() and 0x0F) * 4
                val protocol = buffer[9].toInt() and 0xFF
                
                // Extract Destination IP
                val destIp = "${buffer[16].toInt() and 0xFF}.${buffer[17].toInt() and 0xFF}.${buffer[18].toInt() and 0xFF}.${buffer[19].toInt() and 0xFF}"

                // 2. If it's TCP (Protocol 6), check the port
                if (protocol == 6 && length >= ihl + 4) {
                    val destPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

                    if (destPort == 443) {
                        // INTERCEPT: This is HTTPS traffic. 
                        // Launch a new coroutine to handle this specific connection 
                        // using a dynamic local outbound port.
                        serviceScope.launch {
                            handleLocalOutboundForwarding(destIp, destPort, buffer.copyOfRange(ihl, length))
                        }
                        continue // Don't echo raw packet back, we are proxying it
                    }
                }

                // 3. For non-443 traffic, just pass it through normally (or drop if strict)
                // outputStream.write(buffer, 0, length) 

            } catch (e: Exception) {
                if (!isActive) break
            }
        }
    }

    /**
     * Opens a NEW local outbound socket on a dynamic port 
     * to bypass local state tracking/firewall drops.
     */
    private suspend fun handleLocalOutboundForwarding(destIp: String, destPort: Int, initialPayload: ByteArray) {
        // Get the dynamically selected port from the C++ NDK engine
        val dynamicLocalPort = selector.getActivePort()
        
        try {
            val outboundSocket = Socket()
            
            // BIND to a dynamic local source port before connecting
            // This creates a brand new 4-tuple (SourceIP, NewSourcePort, DestIP, DestPort)
            // escaping any local firewall rules that dropped the previous connection.
            if (dynamicLocalPort > 0) {
                outboundSocket.bind(InetSocketAddress("0.0.0.0", dynamicLocalPort))
            }

            // Connect to the REAL server on 443
            outboundSocket.connect(InetSocketAddress(destIp, destPort), 3000)
            
            // Pipe data between the TUN interface and this new outbound socket
            // (In a full production app, you would handle TCP ACK spoofing here 
            // to keep the originating app happy while the handshake completes)
            
            outboundSocket.close()
        } catch (e: Exception) {
            // If the dynamic port is blocked, the C++ health check will detect it 
            // and rotate to the next available port automatically.
        }
    }

    private fun stopVpn() {
        serviceScope.cancel()
        tunnelInterface?.close()
        tunnelInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        selector.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "PortMawer Active", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val port = selector.getActivePort()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PortMawer Forwarding")
            .setContentText("Outbound via local port :$port")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}