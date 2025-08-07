package org.appdevforall.layouteditor.activities

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.LayoutFile
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.ActivityPreviewLayoutBinding
import com.itsvks.layouteditor.tools.XmlLayoutParser
import com.itsvks.layouteditor.utils.Constants

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