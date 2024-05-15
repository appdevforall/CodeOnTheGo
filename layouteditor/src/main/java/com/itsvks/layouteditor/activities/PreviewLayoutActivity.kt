package com.itsvks.layouteditor.activities

import android.os.Bundle
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.LayoutFile
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.databinding.ActivityPreviewLayoutBinding
import com.itsvks.layouteditor.tools.XmlLayoutParser
import com.itsvks.layouteditor.utils.Constants

class PreviewLayoutActivity : BaseActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val binding = ActivityPreviewLayoutBinding.inflate(
      layoutInflater
    )
    setContentView(binding.getRoot())
    @Suppress("DEPRECATION") val layoutFile = intent.extras!!.getParcelable<LayoutFile>(Constants.EXTRA_KEY_LAYOUT)
    val parser = XmlLayoutParser(this)
    parser.parseFromXml(layoutFile!!.read(), this)
    binding.getRoot().addView(parser.root)
  }
}
