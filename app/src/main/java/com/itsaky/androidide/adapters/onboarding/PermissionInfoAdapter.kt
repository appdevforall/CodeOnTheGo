package com.itsaky.androidide.adapters.onboarding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.LayoutPermissionInfoItemBinding
import com.itsaky.androidide.models.PermissionInfoItem

class PermissionInfoAdapter(private val items: List<PermissionInfoItem>) :
  RecyclerView.Adapter<PermissionInfoAdapter.ViewHolder>() {

  class ViewHolder(val binding: LayoutPermissionInfoItemBinding) :
    RecyclerView.ViewHolder(binding.root)

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    return ViewHolder(
      LayoutPermissionInfoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = items[position]
    holder.binding.text.setText(item.text)
  }

  override fun getItemCount(): Int {
    return items.size
  }
}