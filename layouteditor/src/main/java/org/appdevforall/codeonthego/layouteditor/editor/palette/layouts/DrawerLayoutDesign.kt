package org.appdevforall.codeonthego.layouteditor.editor.palette.layouts

import android.content.Context
import android.graphics.Canvas
import androidx.drawerlayout.widget.DrawerLayout
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class DrawerLayoutDesign(
	context: Context,
) : DrawerLayout(context) {
	private var drawStrokeEnabled = false
	private var isBlueprint = false

	private val logger: Logger = LoggerFactory.getLogger(DrawerLayoutDesign::class.java)

	override fun dispatchDraw(canvas: Canvas) {
		super.dispatchDraw(canvas)

		if (drawStrokeEnabled) {
			Utils.drawDashPathStroke(
				this,
				canvas,
				if (isBlueprint) Constants.BLUEPRINT_DASH_COLOR else Constants.DESIGN_DASH_COLOR,
			)
		}
	}

	override fun draw(canvas: Canvas) {
		if (isBlueprint) {
			Utils.drawDashPathStroke(this, canvas, Constants.BLUEPRINT_DASH_COLOR)
		} else {
			super.draw(canvas)
		}
	}

	fun setStrokeEnabled(enabled: Boolean) {
		drawStrokeEnabled = enabled
		invalidate()
	}

	fun setBlueprint(isBlueprint: Boolean) {
		this.isBlueprint = isBlueprint
		invalidate()
	}

	override fun onAttachedToWindow() {
		try {
			super.onAttachedToWindow()
		} catch (e: Exception) {
			logger.error("Error in previewing DrawerLayoutDesign")
		}
	}
}
