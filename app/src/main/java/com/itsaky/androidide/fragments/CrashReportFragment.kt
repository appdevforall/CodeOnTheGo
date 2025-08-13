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
package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.itsaky.androidide.databinding.LayoutCrashReportBinding
import com.itsaky.androidide.resources.R

class CrashReportFragment : Fragment() {

    private var binding: LayoutCrashReportBinding? = null
    private var closeAppOnClick = true

    companion object {
        const val KEY_CLOSE_APP_ON_CLICK = "close_on_app_click"

        @JvmStatic
        fun newInstance(): CrashReportFragment {
            return newInstance(true)
        }

        @JvmStatic
        fun newInstance(
            closeAppOnClick: Boolean
        ): CrashReportFragment {
            val frag = CrashReportFragment()
            val args = Bundle().apply {
                putBoolean(KEY_CLOSE_APP_ON_CLICK, closeAppOnClick)
            }
            frag.arguments = args
            return frag
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return LayoutCrashReportBinding.inflate(inflater, container, false)
            .also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        closeAppOnClick = args.getBoolean(KEY_CLOSE_APP_ON_CLICK)
        val title = getString(R.string.msg_ide_crashed)
        val message = getString(R.string.msg_crash_info)

        binding!!.apply {
            crashTitle.text = title
            crashSubtitle.text = message

            closeButton.setOnClickListener {
                finishActivity()
            }

            btnOkay.setOnClickListener { finishActivity() }

          // Handle system back button
          requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
              override fun handleOnBackPressed() {
                finishActivity()
              }
            }
          )

        }
    }

    private fun finishActivity() {
        if (closeAppOnClick) {
            requireActivity().finishAffinity()
        } else {
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}