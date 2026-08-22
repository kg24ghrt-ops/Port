package com.example.portmawer

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.portmawer.databinding.ActivityMainBinding
import com.example.portmawer.ui.ConnectionViewModel
import com.example.portmawer.ui.PortAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ConnectionViewModel by viewModels()
    private lateinit var portAdapter: PortAdapter

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        // Port list
        portAdapter = PortAdapter { portData ->
            viewModel.selectPort(portData.port)
        }

        binding.rvPorts.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = portAdapter
        }

        // Connect/Disconnect button
        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            if (state == TunnelState.CONNECTED) {
                viewModel.disconnect()
                stopVpnService()
            } else {
                requestVpnPermissionAndConnect()
            }
        }

        // Refresh/Scan button
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshPorts()
        }
    }

    private fun observeViewModel() {
        viewModel.connectionState.observe(this) { state ->
            updateUIForState(state)
        }

        viewModel.activePort.observe(this) { port ->
            if (port > 0) {
                binding.tvActivePort.text = ":$port"
            } else {
                binding.tvActivePort.text = "--"
            }
        }

        viewModel.portList.observe(this) { ports ->
            portAdapter.submitList(ports)
        }

        viewModel.statusMessage.observe(this) { message ->
            binding.tvStatus.text = message
        }

        viewModel.isScanning.observe(this) { isScanning ->
            binding.progressScan.visibility = if (isScanning) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
    }

    private fun updateUIForState(state: TunnelState) {
        when (state) {
            TunnelState.CONNECTED -> {
                binding.btnConnect.text = "Disconnect"
                binding.btnConnect.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.port_inactive)
                )
                binding.tvConnectionLabel.text = "CONNECTED"
                binding.tvConnectionLabel.setTextColor(
                    ContextCompat.getColor(this, R.color.port_active)
                )
            }
            TunnelState.DISCONNECTED -> {
                binding.btnConnect.text = "Connect"
                binding.btnConnect.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.port_active)
                )
                binding.tvConnectionLabel.text = "DISCONNECTED"
                binding.tvConnectionLabel.setTextColor(
                    ContextCompat.getColor(this, R.color.port_inactive)
                )
            }
            TunnelState.TESTING_PORTS, TunnelState.CONNECTING -> {
                binding.btnConnect.text = "Connecting..."
                binding.btnConnect.isEnabled = false
                binding.tvConnectionLabel.text = state.displayText
                binding.tvConnectionLabel.setTextColor(
                    ContextCompat.getColor(this, R.color.port_testing)
                )
            }
            TunnelState.ERROR -> {
                binding.btnConnect.text = "Retry"
                binding.btnConnect.isEnabled = true
                binding.tvConnectionLabel.text = "ERROR"
                binding.tvConnectionLabel.setTextColor(
                    ContextCompat.getColor(this, R.color.port_inactive)
                )
            }
            TunnelState.RECONNECTING -> {
                binding.tvConnectionLabel.text = "Reconnecting..."
                binding.tvConnectionLabel.setTextColor(
                    ContextCompat.getColor(this, R.color.port_testing)
                )
            }
        }

        if (state != TunnelState.TESTING_PORTS && state != TunnelState.CONNECTING) {
            binding.btnConnect.isEnabled = true
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Permission already granted
            startVpnService()
        }

        // Start port testing in parallel
        viewModel.connect()
    }

    private fun startVpnService() {
        val intent = Intent(this, PortMawerVpnService::class.java).apply {
            action = PortMawerVpnService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, PortMawerVpnService::class.java).apply {
            action = PortMawerVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}