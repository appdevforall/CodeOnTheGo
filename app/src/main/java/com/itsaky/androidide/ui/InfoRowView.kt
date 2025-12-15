package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.itsaky.androidide.R

/**
 * A custom view that displays a label and a value in a vertical layout.
 *
 * This view is used to show project information items (e.g., in a bottom sheet or details screen)
 * where a descriptive label is paired with a specific value.
 *
 * @constructor Creates a new InfoRowView instance.
 * @param context The context the view is running in, through which it can access the current theme, resources, etc.
 * @param attrs The attributes of the XML tag that is inflating the view.
 */
class InfoRowView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val labelView: TextView
    private val valueView: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.layout_project_info_item, this, true)
        labelView = findViewById(R.id.label)
        valueView = findViewById(R.id.value)
    }

    /**
     * Sets the text for the label TextView.
     *
     * @param text The string to be displayed as the label.
     */
    fun setLabel(text: String) {
        labelView.text = text
    }

    /**
     * Sets the text for the value TextView.
     *
     * @param text The string to be displayed as the value.
     */
    fun setValue(text: String) {
        valueView.text = text
    }

    fun setLabelAndValue(label: String, value: String) {
        setLabel(label)
        setValue(value)
    }
}
