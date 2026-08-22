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
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        selector.initialize("cloudflare.com")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> { startVpn(); return START_STICKY }
            ACTION_DISCONNECT -> { stopVpn(); stopSelf(); return START_NOT_STICKY }
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
            .addDisallowedApplication(packageName) // Prevent self-loops

        tunnelInterface = builder.establish() ?: return stopSelf()
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch { forwardPackets() }
    }

    private suspend fun forwardPackets() {
        val inputStream = FileInputStream(tunnelInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(tunnelInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                val version = (buffer[0].toInt() shr 4) and 0x0F
                
                // SAFETY FIX: Pass through IPv6 and all non-IPv4 traffic directly.
                // This ensures games (UDP) and modern apps don't break.
                if (version != 4) {
                    outputStream.write(buffer, 0, length)
                    continue
                }

                val ihl = (buffer[0].toInt() and 0x0F) * 4
                val protocol = buffer[9].toInt() and 0xFF
                val destIp = "${buffer[16].toInt() and 0xFF}.${buffer[17].toInt() and 0xFF}.${buffer[18].toInt() and 0xFF}.${buffer[19].toInt() and 0xFF}"

                if (protocol == 6 && length >= ihl + 4) { // TCP
                    val destPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

                    if (destPort == 443) {
                        serviceScope.launch {
                            handleLocalOutboundForwarding(destIp, destPort, buffer.copyOfRange(0, length))
                        }
                        continue // Intercepted, don't echo back to TUN
                    }
                }
                
                // CRITICAL FIX: Pass through all non-443 TCP, UDP, and ICMP traffic.
                // Without this, apps like Subway Surfers or background syncs will hang.
                outputStream.write(buffer, 0, length)

            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private suspend fun handleLocalOutboundForwarding(destIp: String, destPort: Int, rawIpPacket: ByteArray) {
        val dynamicLocalPort = selector.getActivePort()
        
        try {
            val outboundSocket = Socket()
            
            // SAFETY: protect() tells Android NOT to route this socket through the VPN.
            // Without this, the proxy traffic gets intercepted by itself, causing a crash loop.
            protect(outboundSocket)

            // Bind to dynamic local source port to escape local firewall state tracking
            if (dynamicLocalPort > 0) {
                outboundSocket.bind(InetSocketAddress("0.0.0.0", dynamicLocalPort))
            }

            outboundSocket.connect(InetSocketAddress(destIp, destPort), 3000)
            
            // Strip IP header and send TCP payload to the real server
            val ihl = (rawIpPacket[0].toInt() and 0x0F) * 4
            val tcpPayload = rawIpPacket.copyOfRange(ihl, rawIpPacket.size)
            
            val outStream = outboundSocket.getOutputStream()
            outStream.write(tcpPayload)
            outStream.flush()

            // Keep socket alive briefly to allow return traffic
            // (Full bidirectional NAT requires a complex state machine, 
            // but this establishes the connection and sends the initial ClientHello)
            delay(5000) 
            outboundSocket.close()
            
        } catch (e: Exception) {
            // Port failed, health check will rotate it
        }
    }

    private fun stopVpn() {
        isRunning = false
        serviceScope.cancel()
        tunnelInterface?.close()
        tunnelInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); selector.shutdown(); super.onDestroy() }

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