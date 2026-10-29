package com.autolaunch.appscheduler

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autolaunch.appscheduler.databinding.ItemAppBinding

class AppAdapter(
    private val items: List<AppPickerActivity.AppInfo>,
    private val onClick: (AppPickerActivity.AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.binding.tvAppItemName.text = app.name
        holder.binding.tvAppItemPkg.text = app.packageName
        app.iconResource?.let { holder.binding.ivAppItemIcon.setImageDrawable(it) }
        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = items.size
}
