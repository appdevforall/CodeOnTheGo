package org.appdevforall.codeonthego.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isGone
import androidx.core.view.marginTop
import androidx.core.widget.NestedScrollView
import com.google.android.material.navigation.NavigationBarMenuView
import com.google.android.material.navigationrail.NavigationRailView
import com.itsaky.androidide.R

class IdeNavigationRailView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		defStyleAttr: Int = com.google.android.material.R.attr.navigationRailStyle,
	) : NavigationRailView(context, attrs, defStyleAttr) {
		private val menuMarginTop = resources.getDimensionPixelSize(R.dimen.sidebar_rail_menu_margin_top)
		private var menuScroll: NestedScrollView? = null

		override fun getMaxItemCount(): Int = Int.MAX_VALUE

		override fun onAttachedToWindow() {
			super.onAttachedToWindow()
			enableMenuScrolling()
		}

		override fun onMeasure(
			widthMeasureSpec: Int,
			heightMeasureSpec: Int,
		) {
			super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			val scroll = menuScroll ?: return

			val menuTop = menuTop()
			(scroll.layoutParams as LayoutParams).topMargin = menuTop
			scroll.forceLayout()
			scroll.measure(
				MeasureSpec.makeMeasureSpec(scroll.measuredWidth, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(
					(measuredHeight - paddingTop - paddingBottom - menuTop).coerceAtLeast(0),
					MeasureSpec.EXACTLY,
				),
			)
		}

		override fun onLayout(
			changed: Boolean,
			left: Int,
			top: Int,
			right: Int,
			bottom: Int,
		) {
			super.onLayout(changed, left, top, right, bottom)
			val menu = menuScroll?.getChildAt(0) ?: return
			menu.offsetTopAndBottom(-menu.top)
		}

		private fun menuTop(): Int {
			val header = headerView?.takeUnless { it.isGone } ?: return menuMarginTop
			return header.marginTop + header.measuredHeight + menuMarginTop
		}

		private fun enableMenuScrolling() {
			post {
				val menuView =
					(0 until childCount)
						.map { getChildAt(it) }
						.firstOrNull { it is NavigationBarMenuView }
						?: return@post

				if (menuView.parent is NestedScrollView) return@post

				removeView(menuView)

				val scroll =
					NestedScrollView(context).apply {
						isVerticalScrollBarEnabled = false
						addView(
							menuView,
							LayoutParams(
								LayoutParams.WRAP_CONTENT,
								LayoutParams.WRAP_CONTENT,
							),
						)
					}

				addView(
					scroll,
					LayoutParams(
						LayoutParams.WRAP_CONTENT,
						LayoutParams.MATCH_PARENT,
					),
				)
				menuScroll = scroll
			}
		}
	}
