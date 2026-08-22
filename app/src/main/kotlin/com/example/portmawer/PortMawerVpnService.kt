package com.example.portmawer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class PortMawerVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "portmawer_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.example.portmawer.CONNECT"
        const val ACTION_DISCONNECT = "com.example.portmawer.DISCONNECT"
        private const val TAG = "PortMawerVPN"
    }

    private var tunnelInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val selector = PortSelectorWrapper()
    
    @Volatile
    private var isRunning = false
    
    // Connection tracking for proper NAT
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    
    // Statistics
    private var packetsForwarded = 0L
    private var packetsIntercepted = 0L
    private var bytesForwarded = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        selector.initialize("cloudflare.com")
        Log.i(TAG, "VPN Service created")
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
            .setSession("PortMawer Secure Tunnel")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            // Force secure DNS - bypass router DNS completely
            .addDnsServer("1.1.1.1")      // Cloudflare Primary
            .addDnsServer("1.0.0.1")      // Cloudflare Secondary
            .addDnsServer("8.8.8.8")      // Google Primary
            .addDnsServer("8.8.4.4")      // Google Secondary
            .addDnsServer("9.9.9.9")      // Quad9 (malware blocking)
            .setBlocking(true)
            .addDisallowedApplication(packageName) // Prevent self-loops

        // Add IPv6 support if available
        try {
            builder.addAddress("fd00:1::1", 128)
            builder.addRoute("::", 0)
            builder.addDnsServer("2606:4700:4700::1111") // Cloudflare IPv6
            builder.addDnsServer("2001:4860:4860::8888") // Google IPv6
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 not supported on this network")
        }

        tunnelInterface = builder.establish() ?: run {
            Log.e(TAG, "Failed to establish VPN tunnel")
            stopSelf()
            return
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            forwardPackets()
        }
        
        Log.i(TAG, "VPN tunnel established with secure DNS")
    }

    private suspend fun forwardPackets() {
        val inputStream = FileInputStream(tunnelInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(tunnelInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                packetsForwarded++
                bytesForwarded += length

                val version = (buffer[0].toInt() shr 4) and 0x0F
                
                // Handle IPv6 - pass through for now
                if (version == 6) {
                    outputStream.write(buffer, 0, length)
                    continue
                }
                
                // Only handle IPv4
                if (version != 4) continue

                val ihl = (buffer[0].toInt() and 0x0F) * 4
                val protocol = buffer[9].toInt() and 0xFF
                
                // Extract IPs
                val destIp = "${buffer[16].toInt() and 0xFF}.${buffer[17].toInt() and 0xFF}.${buffer[18].toInt() and 0xFF}.${buffer[19].toInt() and 0xFF}"
                val srcIp = "${buffer[12].toInt() and 0xFF}.${buffer[13].toInt() and 0xFF}.${buffer[14].toInt() and 0xFF}.${buffer[15].toInt() and 0xFF}"

                // Handle TCP traffic
                if (protocol == 6 && length >= ihl + 4) {
                    val destPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                    val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)

                    // Intercept HTTPS traffic (port 443)
                    if (destPort == 443) {
                        packetsIntercepted++
                        val connectionKey = "$srcIp:$srcPort-$destIp:$destPort"
                        
                        serviceScope.launch {
                            handleLocalOutboundForwarding(
                                connectionKey,
                                destIp, 
                                destPort, 
                                srcIp,
                                srcPort,
                                buffer.copyOfRange(0, length),
                                outputStream
                            )
                        }
                        continue
                    }
                }
                
                // Pass through all other traffic (UDP, ICMP, non-443 TCP)
                // This ensures games like Subway Surfers work properly
                outputStream.write(buffer, 0, length)

            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(TAG, "Error in packet forwarding loop", e)
            }
        }
    }

    private suspend fun handleLocalOutboundForwarding(
        connectionKey: String,
        destIp: String, 
        destPort: Int,
        srcIp: String,
        srcPort: Int,
        rawIpPacket: ByteArray,
        tunOutputStream: FileOutputStream
    ) {
        val dynamicLocalPort = selector.getActivePort()
        
        try {
            // Check if we already have a connection for this flow
            var outboundSocket = activeConnections[connectionKey]
            
            if (outboundSocket == null || outboundSocket.isClosed) {
                // Create new socket with dynamic local port binding
                outboundSocket = Socket()
                
                // CRITICAL: Protect socket from VPN to prevent infinite loops
                protect(outboundSocket)

                // Bind to dynamic local source port to escape firewall state tracking
                if (dynamicLocalPort > 0) {
                    outboundSocket.bind(InetSocketAddress("0.0.0.0", dynamicLocalPort))
                    Log.d(TAG, "Bound to local port $dynamicLocalPort for $connectionKey")
                }

                outboundSocket.connect(InetSocketAddress(destIp, destPort), 3000)
                activeConnections[connectionKey] = outboundSocket
                
                Log.i(TAG, "New connection: $connectionKey via local port $dynamicLocalPort")
                
                // Start bidirectional forwarding
                serviceScope.launch {
                    forwardBidirectional(outboundSocket, connectionKey, tunOutputStream, srcIp, srcPort, destIp, destPort)
                }
            }

            // Strip IP header and send TCP payload
            val ihl = (rawIpPacket[0].toInt() and 0x0F) * 4
            val tcpPayload = rawIpPacket.copyOfRange(ihl, rawIpPacket.size)
            
            val outStream = outboundSocket.getOutputStream()
            outStream.write(tcpPayload)
            outStream.flush()
            
        } catch (e: Exception) {
            Log.e(TAG, "Forwarding failed for $connectionKey", e)
            activeConnections.remove(connectionKey)?.close()
        }
    }

    private suspend fun forwardBidirectional(
        socket: Socket,
        connectionKey: String,
        tunOutputStream: FileOutputStream,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int
    ) {
        try {
            val inputStream = socket.getInputStream()
            val buffer = ByteArray(8192)
            
            while (isRunning && !socket.isClosed) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break
                
                // Here you would reconstruct the IP packet with proper checksums
                // For now, we just log that we received data
                Log.d(TAG, "Received $bytesRead bytes from $connectionKey")
                
                // TODO: Full NAT implementation requires:
                // 1. Build new IP header with swapped src/dest
                // 2. Build new TCP header with swapped ports
                // 3. Recalculate IP checksum
                // 4. Recalculate TCP checksum
                // 5. Write to tunOutputStream
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bidirectional forwarding error for $connectionKey", e)
        } finally {
            activeConnections.remove(connectionKey)
            socket.close()
            Log.i(TAG, "Connection closed: $connectionKey")
        }
    }

    private fun stopVpn() {
        isRunning = false
        
        // Close all active connections
        activeConnections.values.forEach { 
            try { it.close() } catch (e: Exception) {}
        }
        activeConnections.clear()
        
        serviceScope.cancel()
        tunnelInterface?.close()
        tunnelInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        Log.i(TAG, "VPN stopped. Stats: $packetsForwarded packets, $packetsIntercepted intercepted, $bytesForwarded bytes")
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
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "PortMawer Active", 
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        val port = selector.getActivePort()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PortMawer Active")
            .setContentText("Secure DNS + Port :$port | Tap to manage")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    fun getStats(): Map<String, Any> {
        return mapOf(
            "packetsForwarded" to packetsForwarded,
            "packetsIntercepted" to packetsIntercepted,
            "bytesForwarded" to bytesForwarded,
            "activeConnections" to activeConnections.size,
            "activePort" to selector.getActivePort()
        )
    }
}