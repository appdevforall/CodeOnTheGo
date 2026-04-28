package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.LayoutGeometryProcessor
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox

class AndroidXmlGenerator(
    private val geometryProcessor: LayoutGeometryProcessor
) {
    internal fun buildXml(
        boxes: List<ScaledBox>,
        annotations: Map<ScaledBox, String>,
        targetDpHeight: Int,
        wrapInScroll: Boolean
    ): Pair<String, String> {
        val context = XmlContext()
        val maxBottom = boxes.maxOfOrNull { it.y + it.h } ?: 0
        val needScroll = wrapInScroll && maxBottom > targetDpHeight

        appendHeaders(context, needScroll)

        val layoutItems = geometryProcessor.buildLayoutTree(boxes)
        val renderer = LayoutRenderer(context, annotations)

        layoutItems.forEach { item -> renderer.render(item, "        ") }

        appendFooters(context, needScroll)

        val layoutXml = context.toString()
        val stringsXml = generateStringsResourceXml(context)

        return Pair(layoutXml, stringsXml)
    }

    private fun generateStringsResourceXml(context: XmlContext): String {
        if (context.stringArrays.isEmpty()) return ""

        val builder = StringBuilder()
        context.stringArrays.forEach { (name, items) ->
            builder.appendLine("    <string-array name=\"${name}\">")
            items.forEach { item ->
                builder.appendLine("        <item>${item.escapeXmlAttr()}</item>")
            }
            builder.appendLine("    </string-array>")
        }

        return builder.toString().trimEnd()
    }

    private fun appendHeaders(context: XmlContext, needScroll: Boolean) {
        val namespaces = """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" xmlns:tools="http://schemas.android.com/tools""""
        context.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")

        if (needScroll) {
            context.appendLine("<ScrollView $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:fillViewport=\"true\">")
            context.appendLine("    <LinearLayout android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        } else {
            context.appendLine("<LinearLayout $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        }
        context.appendLine()
    }

    private fun appendFooters(context: XmlContext, needScroll: Boolean) {
        context.appendLine(if (needScroll) "    </LinearLayout>\n</ScrollView>" else "</LinearLayout>")
    }
}
