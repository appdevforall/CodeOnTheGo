package org.appdevforall.codeonthego.layouteditor.tools

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap
import org.xmlpull.v1.XmlPullParser

object XmlParserUtils {

    fun extractAttributes(parser: XmlPullParser): AttributeMap {
        val map = AttributeMap()

        for (i in 0 until parser.attributeCount) {
            map.putValue(
                parser.getAttributeName(i),
                parser.getAttributeValue(i)
            )
        }

        return map
    }

    fun getAttribute(
        parser: XmlPullParser,
        name: String
    ): String? =
        (0 until parser.attributeCount)
            .firstOrNull { parser.getAttributeName(it) == name }
            ?.let { parser.getAttributeValue(it) }

    fun applyAttributes(
        parser: XmlPullParser,
        target: View,
        attributeMap: MutableMap<View, AttributeMap>,
        marker: String,
        skip: String? = null
    ) {
        val map = attributeMap[target] ?: AttributeMap()
        map.putValue(marker, "true")

        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)
            if (attrName == skip) continue
            map.putValue(attrName, parser.getAttributeValue(i))
        }

        attributeMap[target] = map
    }

    fun createIncludePlaceholder(
        context: Context,
        attributeMap: MutableMap<View, AttributeMap>,
        marker: String
    ): View = View(context).also {
        val attrs = AttributeMap().apply { putValue(marker, "true") }
        attributeMap[it] = attrs
    }

    fun createMergeWrapper(context: Context): FrameLayout =
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
}