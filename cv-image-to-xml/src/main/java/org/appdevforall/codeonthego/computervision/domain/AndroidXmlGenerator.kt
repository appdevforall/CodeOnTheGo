package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import kotlin.text.substringAfterLast

class AndroidXmlGenerator(
    private val geometryProcessor: LayoutGeometryProcessor
) {
    companion object {
        private val WIDGET_TAGS = setOf("Switch", "CheckBox", "RadioButton")
    }

    internal fun buildXml(
        boxes: List<ScaledBox>,
        annotations: Map<ScaledBox, String>,
        targetDpHeight: Int,
        wrapInScroll: Boolean
    ): String {
        val xml = StringBuilder()
        val maxBottom = boxes.maxOfOrNull { it.y + it.h } ?: 0
        val needScroll = wrapInScroll && maxBottom > targetDpHeight
        val namespaces =
            """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" xmlns:tools="http://schemas.android.com/tools""""

        xml.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        if (needScroll) {
            xml.appendLine("<ScrollView $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:fillViewport=\"true\">")
            xml.appendLine("    <LinearLayout android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        } else {
            xml.appendLine("<LinearLayout $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        }
        xml.appendLine()

        val rows = geometryProcessor.groupIntoRows(boxes)
        val counters = mutableMapOf<String, Int>()
        rows.forEach { row ->
            if (row.size == 1) {
                appendSimpleView(xml, row.first(), counters, "        ", annotations)
            } else {
                appendHorizontalRow(xml, row, counters, annotations)
            }
            xml.appendLine()
        }

        xml.appendLine(if (needScroll) "    </LinearLayout>\n</ScrollView>" else "</LinearLayout>")
        return xml.toString()
    }

    private fun appendHorizontalRow(
        xml: StringBuilder,
        row: List<ScaledBox>,
        counters: MutableMap<String, Int>,
        annotations: Map<ScaledBox, String>
    ) {
        xml.appendLine(
            """
            |        <LinearLayout
            |            android:layout_width="match_parent"
            |            android:layout_height="wrap_content"
            |            android:orientation="horizontal"
            |            android:baselineAligned="false">
            """.trimMargin()
        )

        row.forEachIndexed { index, box ->
            val extraAttrs = if (index < row.lastIndex) {
                val nextBox = row[index + 1]
                val gap = (nextBox.x - (box.x + box.w))
                val marginEnd = maxOf(0, gap)

                mapOf("android:layout_marginEnd" to "${marginEnd}dp")
            } else {
                emptyMap()
            }
            appendSimpleView(xml, box, counters, "            ", annotations, extraAttrs)
            xml.appendLine()
        }

        xml.append("        </LinearLayout>")
    }

    private fun escapeXmlAttr(value: String): String =
        value.trim()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun viewTagFor(label: String): String = when (label) {
        "text" -> "TextView"
        "button" -> "Button"
        "image_placeholder", "icon" -> "ImageView"
        "checkbox_unchecked", "checkbox_checked" -> "CheckBox"
        "radio_button_unchecked", "radio_button_checked" -> "RadioButton"
        "switch_off", "switch_on" -> "Switch"
        "text_entry_box" -> "EditText"
        "dropdown" -> "Spinner"
        "card" -> "androidx.cardview.widget.CardView"
        "slider" -> "com.google.android.material.slider.Slider"
        else -> "View"
    }

    private fun parseMarginAnnotations(annotation: String?, tag: String): Map<String, String> {
        return FuzzyAttributeParser.parse(annotation, tag)
    }

    private fun appendSimpleView(
        xml: StringBuilder,
        box: ScaledBox,
        counters: MutableMap<String, Int>,
        indent: String,
        annotations: Map<ScaledBox, String>,
        extraAttrs: Map<String, String> = emptyMap()
    ) {
        val label = box.label
        val tag = viewTagFor(label)
        val count = counters.getOrPut(label) { 0 }.let { counters[label] = it + 1; it }
        val defaultId = "${label.replace(Regex("[^a-zA-Z0-9_]"), "_")}_$count"

        val parsedAttrs = parseMarginAnnotations(annotations[box], tag)
        val attrs = extraAttrs + parsedAttrs

        val width = attrs["android:layout_width"] ?: "wrap_content"
        val height = attrs["android:layout_height"] ?: "wrap_content"
        val id = attrs["android:id"]?.substringAfterLast('/') ?: defaultId

        val writtenAttrs = mutableSetOf(
            "android:id", "android:layout_width", "android:layout_height"
        )

        xml.append("$indent<$tag\n")
        xml.append("$indent    android:id=\"@+id/${escapeXmlAttr(id)}\"\n")
        xml.append("$indent    android:layout_width=\"${escapeXmlAttr(width)}\"\n")
        xml.append("$indent    android:layout_height=\"${escapeXmlAttr(height)}\"\n")

        when (tag) {
            "TextView", "Button", "CheckBox", "RadioButton", "Switch" ->
                appendTextWidgetAttributes(xml, indent, parsedAttrs, box, label, tag, writtenAttrs)

            "EditText" ->
                appendEditTextAttributes(xml, indent, parsedAttrs, box, writtenAttrs)

            "ImageView" ->
                appendImageViewAttributes(xml, indent, parsedAttrs, label, writtenAttrs)
        }

        attrs.forEach { (key, value) ->
            if (key !in writtenAttrs) {
                xml.append("$indent    $key=\"${escapeXmlAttr(value)}\"\n")
                writtenAttrs.add(key)
            }
        }
        xml.append("$indent/>")
    }

    private fun appendTextWidgetAttributes(
        xml: StringBuilder,
        indent: String,
        parsedAttrs: Map<String, String>,
        box: ScaledBox,
        label: String,
        tag: String,
        writtenAttrs: MutableSet<String>
    ) {
        val rawViewText = parsedAttrs["android:text"]
            ?: box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: if (tag in WIDGET_TAGS) tag else box.label

        xml.append("$indent    android:text=\"${escapeXmlAttr(rawViewText)}\"\n")
        writtenAttrs.add("android:text")
        if (tag == "TextView") {
            val textSize = parsedAttrs["android:textSize"] ?: "16sp"
            xml.append("$indent    android:textSize=\"${escapeXmlAttr(textSize)}\"\n")
            writtenAttrs.add("android:textSize")
        }
        if (label.contains("_checked") || label.contains("_on")) {
            val checked = parsedAttrs["android:checked"] ?: "true"
            xml.append("$indent    android:checked=\"${escapeXmlAttr(checked)}\"\n")
            writtenAttrs.add("android:checked")
        }
        xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
        writtenAttrs.add("tools:ignore")
    }

    private fun appendEditTextAttributes(
        xml: StringBuilder,
        indent: String,
        parsedAttrs: Map<String, String>,
        box: ScaledBox,
        writtenAttrs: MutableSet<String>
    ) {
        val rawHint = parsedAttrs["android:hint"] ?: box.text.ifEmpty { "Enter text..." }

        xml.append("$indent    android:hint=\"${escapeXmlAttr(rawHint)}\"\n")
        writtenAttrs.add("android:hint")

        val inputType = parsedAttrs["android:inputType"] ?: "text"
        xml.append("$indent    android:inputType=\"${escapeXmlAttr(inputType)}\"\n")
        writtenAttrs.add("android:inputType")

        xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
        writtenAttrs.add("tools:ignore")
    }

    private fun appendImageViewAttributes(
        xml: StringBuilder,
        indent: String,
        parsedAttrs: Map<String, String>,
        label: String,
        writtenAttrs: MutableSet<String>
    ) {
        val contentDescription = parsedAttrs["android:contentDescription"] ?: label
        xml.append("$indent    android:contentDescription=\"${escapeXmlAttr(contentDescription)}\"\n")
        writtenAttrs.add("android:contentDescription")
    }
}
