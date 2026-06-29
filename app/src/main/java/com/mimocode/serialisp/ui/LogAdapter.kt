package com.mimocode.serialisp.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mimocode.serialisp.R

data class LogEntry(val message: String, val level: String)

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private val entries = mutableListOf<LogEntry>()

    private val colors = mapOf(
        "ok" to Color.parseColor("#3FB950"),
        "err" to Color.parseColor("#F85149"),
        "warn" to Color.parseColor("#D29922"),
        "step" to Color.parseColor("#BC8CFF"),
        "data" to Color.parseColor("#8B949E"),
        "rx" to Color.parseColor("#58A6FF"),
        "tx" to Color.parseColor("#F0883E"),
        "info" to Color.parseColor("#E6EDF3")
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLog: TextView = view.findViewById(R.id.tvLog)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvLog.text = entry.message
        holder.tvLog.setTextColor(colors[entry.level] ?: Color.parseColor("#E6EDF3"))
    }

    override fun getItemCount() = entries.size

    fun addEntry(message: String, level: String = "info") {
        entries.add(LogEntry(message, level))
        notifyItemInserted(entries.size - 1)
    }

    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }
}
