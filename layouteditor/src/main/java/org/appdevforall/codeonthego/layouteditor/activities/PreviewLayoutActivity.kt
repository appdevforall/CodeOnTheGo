package org.appdevforall.codeonthego.layouteditor.activities

import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import org.appdevforall.codeonthego.layouteditor.BaseActivity
import org.appdevforall.codeonthego.layouteditor.LayoutFile
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityPreviewLayoutBinding
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutParser
import org.appdevforall.codeonthego.layouteditor.utils.Constants

class PreviewLayoutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPreviewLayoutBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())
        @Suppress("DEPRECATION") val layoutFile =
            intent.extras!!.getParcelable<LayoutFile>(Constants.EXTRA_KEY_LAYOUT)
        val parser = XmlLayoutParser(this)
        layoutFile?.readDesignFile()?.let { parser.parseFromXml(it, this) }

        val previewContainer = binding.root.findViewById<ViewGroup>(R.id.preview_container)

        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )

        previewContainer.addView(parser.root, layoutParams)
    }
}