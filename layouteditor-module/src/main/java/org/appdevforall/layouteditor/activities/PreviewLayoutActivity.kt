package org.appdevforall.layouteditor.activities

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import org.appdevforall.layouteditor.BaseActivity
import org.appdevforall.layouteditor.LayoutFile
import org.appdevforall.layouteditor.R
import org.appdevforall.layouteditor.databinding.ActivityPreviewLayoutBinding
import org.appdevforall.layouteditor.tools.XmlLayoutParser
import org.appdevforall.layouteditor.utils.Constants

class PreviewLayoutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPreviewLayoutBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())
        @Suppress("DEPRECATION") val layoutFile =
            intent.extras!!.getParcelable<LayoutFile>(Constants.EXTRA_KEY_LAYOUT)
        val parser = XmlLayoutParser(this)
        parser.parseFromXml(layoutFile!!.readDesignFile(), this)

        val previewContainer = binding.root.findViewById<ViewGroup>(R.id.preview_container)

        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )

        previewContainer.addView(parser.root, layoutParams)
    }
}