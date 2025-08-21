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

package com.itsaky.androidide.fragments.onboarding

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.itsaky.androidide.databinding.FragmentOnboardingGreetingBinding
import com.itsaky.androidide.fragments.FragmentWithBinding

/**
 * @author Akash Yadav
 */
class GreetingFragment :
  FragmentWithBinding<FragmentOnboardingGreetingBinding>(FragmentOnboardingGreetingBinding::inflate){

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    val binding = FragmentOnboardingGreetingBinding.inflate(inflater, container, false)
    val view = binding.root

      // Code to crash app
      // TODO - Remove after testing Sentry integration
    binding.icon.setOnLongClickListener {
        val numerator = 10
        val denominator = 0
        // Dividing by zero
        val result = numerator / denominator
        Log.d("CrashTest", "Result: $result")
      true
    }
    return view
  }

}

