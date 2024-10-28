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

package com.itsaky.androidide.utils

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.itsaky.androidide.common.R

class OrientationUtilities {

    companion object {
        fun setOrientation(function: () -> Unit) {
            // not sure what limitations we should add here.
            // But we will add something when we will figure it out.
            function.invoke()
        }

        fun getDeviceDefaultOrientation(context: Context): Int {
            val display =
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val rotation = display.rotation
            val metrics = DisplayMetrics()
            display.getMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels

            return if ((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) &&
                height > width || (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && width > height
            ) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
        }

        private fun isTablet(context: Context): Boolean {
            return ((context.resources.configuration.screenLayout
                    and Configuration.SCREENLAYOUT_SIZE_MASK)
                    >= Configuration.SCREENLAYOUT_SIZE_LARGE)
        }
    }

}