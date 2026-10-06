@file:JvmName("PaneStyling")

package org.appdevforall.codeonthego.plugins.ai.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textfield.TextInputLayout

/**
 * One button emphasis, from the plugin's own colour resources.
 *
 * @property content the label and icon
 * @property ripple the ripple
 * @property container the fill; null leaves the button transparent
 * @property stroke the border; null draws none
 */
data class ButtonColors(
	@ColorRes val content: Int,
	@ColorRes val ripple: Int,
	@ColorRes val container: Int? = null,
	@ColorRes val stroke: Int? = null,
)

/**
 * An outlined-box text field, from the plugin's own colour resources.
 *
 * @property stroke the outline; a selector with a colour per state, since a plain colour recolours
 *   only the focused outline
 * @property error the outline while the field shows an error
 * @property hint the hint
 * @property endIcon the end icon's tint
 */
data class FieldColors(
	@ColorRes val stroke: Int,
	@ColorRes val error: Int,
	@ColorRes val hint: Int,
	@ColorRes val endIcon: Int,
)

/**
 * The plugin's own resources a settings pane is dressed in. They resolve against each view's
 * context, which carries the plugin's resources, not the host's.
 *
 * @property filledButton a section's primary action
 * @property outlinedButton every secondary action
 * @property field every text field
 * @property divider a divider's hairline colour
 * @property buttonStrokeWidth a bordered button's border width
 * @property cornerRadius the corner radius of buttons and text fields
 * @property dividerThickness a divider's thickness
 */
data class PaneStyle(
	val filledButton: ButtonColors,
	val outlinedButton: ButtonColors,
	val field: FieldColors,
	@ColorRes val divider: Int,
	@DimenRes val buttonStrokeWidth: Int,
	@DimenRes val cornerRadius: Int,
	@DimenRes val dividerThickness: Int,
)

/**
 * Gives every Material button, text field and divider under [this] its Material 3 colours, outline
 * and ripple in code. The styles' `app:` items are dropped inside the host, so XML alone leaves
 * these controls on the host theme's values.
 *
 * @param style the plugin's resources to apply.
 * @param outlinedButtonIds the buttons that are secondary actions; every other button is filled,
 *   so a section has one filled action and the rest outlined.
 *
 * A styled control's own children are left alone: they are the control's internals, not the pane's.
 */
fun View.applyPaneStyling(
	style: PaneStyle,
	outlinedButtonIds: Set<Int>,
) {
	when (this) {
		is MaterialButton -> applyColors(if (id in outlinedButtonIds) style.outlinedButton else style.filledButton, style)
		is TextInputLayout -> applyColors(style.field, style)
		is MaterialDivider -> applyHairline(style)
		is ViewGroup -> for (i in 0 until childCount) getChildAt(i).applyPaneStyling(style, outlinedButtonIds)
	}
}

/** Container, label, icon, border and ripple, each with its disabled state. */
private fun MaterialButton.applyColors(
	colors: ButtonColors,
	style: PaneStyle,
) {
	val content = colorList(colors.content)
	backgroundTintList = colors.container?.let { colorList(it) } ?: ColorStateList.valueOf(Color.TRANSPARENT)
	setTextColor(content)
	iconTint = content
	rippleColor = colorList(colors.ripple)
	colors.stroke?.let { strokeColor = colorList(it) }
	strokeWidth = if (colors.stroke == null) 0 else resources.getDimensionPixelSize(style.buttonStrokeWidth)
	cornerRadius = resources.getDimensionPixelSize(style.cornerRadius)
}

/** Outline, corners, hint and end icon of an outlined-box field. */
private fun TextInputLayout.applyColors(
	colors: FieldColors,
	style: PaneStyle,
) {
	setBoxStrokeColorStateList(colorList(colors.stroke))
	setBoxStrokeErrorColor(colorList(colors.error))
	val radius = resources.getDimension(style.cornerRadius)
	setBoxCornerRadii(radius, radius, radius, radius)
	val hint = colorList(colors.hint)
	defaultHintTextColor = hint
	hintTextColor = hint
	setEndIconTintList(colorList(colors.endIcon))
}

private fun MaterialDivider.applyHairline(style: PaneStyle) {
	setDividerColorResource(style.divider)
	setDividerThicknessResource(style.dividerThickness)
}

/** Resolved against this view's context, which carries the plugin's resources. */
private fun View.colorList(
	@ColorRes id: Int,
): ColorStateList = requireNotNull(ContextCompat.getColorStateList(context, id))
