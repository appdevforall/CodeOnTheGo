/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.adapters.onboarding

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.SizeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutOnboardingPermissionItemBinding
import com.itsaky.androidide.models.OnboardingPermissionItem

/**
 * @author Akash Yadav
 */
class OnboardingPermissionsAdapter(private val permissions: List<OnboardingPermissionItem>,
  private val requestPermission: (String) -> Unit) :
  RecyclerView.Adapter<OnboardingPermissionsAdapter.ViewHolder>() {

  class ViewHolder(val binding: LayoutOnboardingPermissionItemBinding) :
    RecyclerView.ViewHolder(binding.root) {
      val titleColor: Int = MaterialColors.getColor(binding.root, R.attr.colorOnSurface)
      val descriptionColor: Int = MaterialColors.getColor(binding.root, R.attr.colorOnSurfaceVariant)
      val disabledTitleColor: Int = ColorUtils.setAlphaComponent(titleColor, (255 * 0.38f).toInt())
      val disabledDescriptionColor: Int = ColorUtils.setAlphaComponent(descriptionColor, (255 * 0.38f).toInt())
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    return ViewHolder(
      LayoutOnboardingPermissionItemBinding.inflate(LayoutInflater.from(parent.context), parent,
        false))
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val binding = holder.binding
    val permission = permissions[position]
    val context = binding.root.context

    binding.infoContent.apply {
      title.setText(permission.title)
      description.setText(permission.description)
      title.setTextColor(if (permission.isSupportedOnDevice) holder.titleColor else holder.disabledTitleColor)
      description.setTextColor(if (permission.isSupportedOnDevice) holder.descriptionColor else holder.disabledDescriptionColor)
    }

    binding.grantButton.apply {
      isEnabled = permission.isSupportedOnDevice
      text = context.getString(R.string.title_grant)
      icon = null
      iconTint = null
      iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
      iconPadding = 0
      iconSize = 0
    }

    binding.grantButton.setOnClickListener {
      requestPermission(permission.permission)
    }

    if (permission.isGranted) {
      binding.grantButton.apply {
        isEnabled = false
        text = ""
        icon = ContextCompat.getDrawable(context, R.drawable.ic_ok)
        iconTint = ColorStateList.valueOf(
          ContextCompat.getColor(context, R.color.green_500))
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
        iconPadding = 0
        iconSize = SizeUtils.dp2px(28f)
      }
    }
  }

  override fun getItemCount(): Int {
    return permissions.size
  }
}
