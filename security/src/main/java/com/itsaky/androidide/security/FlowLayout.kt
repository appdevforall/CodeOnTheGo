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

package com.itsaky.androidide.security

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Lays out children in horizontal rows, wrapping to a new row whenever a child would exceed the
 * available line width. Used by the cryptogram disguise grid so letter cells reflow at any font
 * scale instead of being clipped.
 */
class FlowLayout
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		defStyleAttr: Int = 0,
	) : ViewGroup(context, attrs, defStyleAttr) {
		private val lines = mutableListOf<List<View>>()
		private val lineHeights = mutableListOf<Int>()

		override fun onMeasure(
			widthMeasureSpec: Int,
			heightMeasureSpec: Int,
		) {
			val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
			val widthMode = MeasureSpec.getMode(widthMeasureSpec)

			lines.clear()
			lineHeights.clear()

			var currentLine = mutableListOf<View>()
			var currentLineWidth = 0
			var currentLineHeight = 0
			var maxLineWidth = 0

			for (i in 0 until childCount) {
				val child = getChildAt(i)
				if (child.visibility == View.GONE) continue

				measureChild(child, widthMeasureSpec, heightMeasureSpec)
				val childWidth = child.measuredWidth
				val childHeight = child.measuredHeight

				if (currentLineWidth + childWidth > availableWidth && currentLine.isNotEmpty()) {
					lines.add(currentLine)
					lineHeights.add(currentLineHeight)
					maxLineWidth = maxOf(maxLineWidth, currentLineWidth)

					currentLine = mutableListOf()
					currentLineWidth = 0
					currentLineHeight = 0
				}

				currentLine.add(child)
				currentLineWidth += childWidth
				currentLineHeight = maxOf(currentLineHeight, childHeight)
			}

			if (currentLine.isNotEmpty()) {
				lines.add(currentLine)
				lineHeights.add(currentLineHeight)
				maxLineWidth = maxOf(maxLineWidth, currentLineWidth)
			}

			val totalHeight = paddingTop + paddingBottom + lineHeights.sum()
			val totalWidth =
				if (widthMode == MeasureSpec.EXACTLY) {
					MeasureSpec.getSize(widthMeasureSpec)
				} else {
					maxLineWidth + paddingLeft + paddingRight
				}

			setMeasuredDimension(
				resolveSize(totalWidth, widthMeasureSpec),
				resolveSize(totalHeight, heightMeasureSpec),
			)
		}

		override fun onLayout(
			changed: Boolean,
			l: Int,
			t: Int,
			r: Int,
			b: Int,
		) {
			var top = paddingTop
			for (lineIdx in lines.indices) {
				val line = lines[lineIdx]
				val lineHeight = lineHeights[lineIdx]
				var left = paddingLeft

				for (child in line) {
					val childWidth = child.measuredWidth
					val childHeight = child.measuredHeight
					val childTop = top + (lineHeight - childHeight) / 2
					child.layout(left, childTop, left + childWidth, childTop + childHeight)
					left += childWidth
				}
				top += lineHeight
			}
		}
	}
