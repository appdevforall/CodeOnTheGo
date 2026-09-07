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

package com.itsaky.androidide.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

/** The plot size every metrics chart test lays out at; roughly the carousel strip on a phone. */
const val CHART_WIDTH = 720

const val CHART_HEIGHT = 400

/**
 * Lays this chart out and draws it once, which is what every assertion about its viewport needs.
 *
 * Without the layout the plot area has no extent, so every coordinate lands on its edge, a hit test
 * cannot tell inside from outside, and the renderer has nothing to place its viewport in. The draw
 * is what renders the axes and annotations that assertions about them read back.
 *
 * Four test classes had grown their own copy of this, with the comment explaining it in three of
 * them and the draw missing from one.
 */
fun SafeLineChart.layOutAndDraw(
	width: Int = CHART_WIDTH,
	height: Int = CHART_HEIGHT,
) {
	measure(
		View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
		View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
	)
	layout(0, 0, width, height)
	draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
}
