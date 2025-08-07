package org.appdevforall.layouteditor.activities

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.ActionBar
import org.appdevforall.layouteditor.BaseActivity
import org.appdevforall.layouteditor.R
import org.appdevforall.layouteditor.databinding.ActivityPreviewDrawableBinding
import org.appdevforall.layouteditor.views.AlphaPatternDrawable

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
