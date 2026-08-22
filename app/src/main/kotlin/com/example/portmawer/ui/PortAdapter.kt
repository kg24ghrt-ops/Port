package com.example.portmawer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.portmawer.PortData
import com.example.portmawer.R

class PortAdapter(
    private val onPortClick: (PortData) -> Unit
) : ListAdapter<PortData, PortAdapter.PortViewHolder>(PortDiffCallback()) {

    class PortViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val portNumber: TextView = itemView.findViewById(R.id.tvPortNumber)
        val portStatus: TextView = itemView.findViewById(R.id.tvPortStatus)
        val portLatency: TextView = itemView.findViewById(R.id.tvPortLatency)
        val statusDot: View = itemView.findViewById(R.id.statusDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PortViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_port, parent, false)
        return PortViewHolder(view)
    }

    override fun onBindViewHolder(holder: PortViewHolder, position: Int) {
        val portData = getItem(position)

        holder.portNumber.text = ":${portData.port}"

        // Status text
        holder.portStatus.text = portData.statusText

        // Latency display
        if (portData.isActive && portData.latencyMs >= 0) {
            holder.portLatency.text = "${portData.latencyMs}ms"
            holder.portLatency.visibility = View.VISIBLE
        } else {
            holder.portLatency.visibility = View.GONE
        }

        // Color coding
        val context = holder.itemView.context
        val statusColor = when {
            portData.isSelected -> ContextCompat.getColor(context, R.color.port_selected)
            portData.isActive -> ContextCompat.getColor(context, R.color.port_active)
            else -> ContextCompat.getColor(context, R.color.port_inactive)
        }

        holder.statusDot.setBackgroundColor(statusColor)
        holder.portStatus.setTextColor(statusColor)

        // Selected state background
        holder.itemView.isActivated = portData.isSelected

        // Click handler
        holder.itemView.setOnClickListener {
            onPortClick(portData)
        }
    }

    class PortDiffCallback : DiffUtil.ItemCallback<PortData>() {
        override fun areItemsTheSame(oldItem: PortData, newItem: PortData): Boolean {
            return oldItem.port == newItem.port
        }

        override fun areContentsTheSame(oldItem: PortData, newItem: PortData): Boolean {
            return oldItem == newItem
        }
    }
}