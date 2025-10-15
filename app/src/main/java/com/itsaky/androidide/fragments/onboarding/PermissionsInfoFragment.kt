package com.itsaky.androidide.fragments.onboarding

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.onboarding.PermissionInfoAdapter
import com.itsaky.androidide.databinding.FragmentPermissionsInfoBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.models.PermissionInfoItem

class PermissionsInfoFragment :
    FragmentWithBinding<FragmentPermissionsInfoBinding>(FragmentPermissionsInfoBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val items = listOf(
            PermissionInfoItem(R.string.permissions_info_notifications),
            PermissionInfoItem(R.string.permissions_info_storage),
            PermissionInfoItem(R.string.permissions_info_install),
            PermissionInfoItem(R.string.permissions_info_overlay_accessibility)
        )

        binding.permissionsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = PermissionInfoAdapter(items)
        }
    }
}