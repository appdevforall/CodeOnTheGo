package com.itsvks.layouteditor.tools

import android.annotation.SuppressLint
import android.content.Context
import android.widget.TextView
import com.itsvks.layouteditor.R
import com.skydoves.colorpickerview.AlphaTileView
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.flag.FlagView

class ColorPickerDialogFlag(context: Context) :
  FlagView(context, R.layout.layout_color_dialog_flag) {

  private val textView: TextView = findViewById(R.id.flag_color_code)
  private val alphaTileView: AlphaTileView = findViewById(R.id.flag_color_layout)

  @SuppressLint("SetTextI18n")
  override fun onRefresh(colorEnvelope: ColorEnvelope) {
    textView.text = "#${colorEnvelope.hexCode}"
    alphaTileView.setPaintColor(colorEnvelope.color)
  }

  override fun onFlipped(isFlipped: Boolean) {}
}