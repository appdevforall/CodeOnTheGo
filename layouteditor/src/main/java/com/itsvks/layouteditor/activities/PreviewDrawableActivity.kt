package com.itsvks.layouteditor.activities

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.ActionBar
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.ActivityPreviewDrawableBinding
import com.itsvks.layouteditor.views.AlphaPatternDrawable

class PreviewDrawableActivity : BaseActivity() {
  private lateinit var binding: ActivityPreviewDrawableBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityPreviewDrawableBinding.inflate(layoutInflater)
    setContentView(binding.getRoot())

    setSupportActionBar(binding.topAppBar)
    supportActionBar!!.setTitle(R.string.preview_drawable)

    binding.topAppBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    binding.background.setImageDrawable(AlphaPatternDrawable(24))

    onLoad(binding.mainImage, supportActionBar)
  }

  //todo remove and replace this with some reasonable replacement.
  companion object {
    @JvmStatic
    var onLoad: (ImageView, ActionBar?) -> Unit = { _, _ -> }
  }
}
