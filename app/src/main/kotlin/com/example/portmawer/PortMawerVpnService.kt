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
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * VpnService that intercepts traffic and reroutes port 443
 * through the dynamically selected port.
 */
class PortMawerVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "portmawer_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.example.portmawer.CONNECT"
        const val ACTION_DISCONNECT = "com.example.portmawer.DISCONNECT"
    }

    private var tunnelInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val selector = PortSelectorWrapper()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        if (isRunning) return

        // Build the VPN tunnel
        val builder = Builder()
            .setSession("PortMawer")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) // Route all traffic
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(true)

        // Allow the app itself to bypass the VPN (for port testing)
        builder.addDisallowedApplication(packageName)

        tunnelInterface = builder.establish()
        if (tunnelInterface == null) {
            stopSelf()
            return
        }

        isRunning = true

        // Show notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Start packet forwarding thread
        Thread {
            forwardPackets()
        }.start()
    }

    private fun forwardPackets() {
        val inputStream = FileInputStream(tunnelInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(tunnelInterface!!.fileDescriptor)
        val buffer = ByteArray(32767) // Max packet size

        val activePort = selector.getActivePort()

        while (isRunning) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                // Here you would implement the actual packet rewriting:
                // 1. Parse the IP header
                // 2. Check if destination port is 443
                // 3. If yes, rewrite to activePort
                // 4. Forward the packet

                // For now, just echo back (placeholder)
                outputStream.write(buffer, 0, length)

            } catch (e: Exception) {
                if (isRunning) {
                    // Log error but continue
                }
                break
            }
        }

        inputStream.close()
        outputStream.close()
    }

    private fun stopVpn() {
        isRunning = false
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
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PortMawer VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when PortMawer is actively routing traffic"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, PortMawerVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val activePort = selector.getActivePort()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PortMawer Active")
            .setContentText("Routing via port :$activePort")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .addAction(0, "Disconnect", disconnectPending)
            .setOngoing(true)
            .build()
    }
}