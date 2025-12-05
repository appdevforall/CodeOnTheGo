package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.itsaky.androidide.R

class InfoRowView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val labelView: TextView
    private val valueView: TextView

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.layout_project_info_item, this, true)
        labelView = findViewById(R.id.label)
        valueView = findViewById(R.id.value)
    }

    fun setLabel(text: String) {
        labelView.text = text
    }

    fun setValue(text: String) {
        valueView.text = text
    }
}
