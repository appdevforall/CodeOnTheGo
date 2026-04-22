package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.FuzzyAttributeParser

class LayoutRenderer(
    private val context: XmlContext,
    private val annotations: Map<ScaledBox, String>
) {
    fun render(item: LayoutItem, indent: String = "        ") {
        when (item) {
            is LayoutItem.SimpleView -> renderSimpleView(item.box, indent)
            is LayoutItem.HorizontalRow -> renderHorizontalRow(item.row, indent)
            is LayoutItem.RadioGroup -> renderRadioGroup(item.boxes, item.orientation, indent)
        }
        context.appendLine()
    }

    private fun renderSimpleView(
        box: ScaledBox,
        indent: String,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null,
        parsedAttrsOverride: Map<String, String>? = null
    ) {
        val tag = AndroidWidget.getTagFor(box.label)
        val parsedAttrs = parsedAttrsOverride ?: FuzzyAttributeParser.parse(annotations[box], tag)

        val widget = AndroidWidget.create(box, parsedAttrs)
        widget.render(context, indent, extraAttrs, idOverride)
    }

    private fun renderHorizontalRow(row: List<ScaledBox>, indent: String) {
        context.appendLine(
            """
            |$indent<LinearLayout
            |$indent    android:layout_width="match_parent"
            |$indent    android:layout_height="wrap_content"
            |$indent    android:orientation="horizontal"
            |$indent    android:baselineAligned="false">
            """.trimMargin()
        )

        row.forEachIndexed { index, box ->
            val extraAttrs = if (index < row.lastIndex) {
                val nextBox = row[index + 1]
                val gap = maxOf(0, nextBox.x - (box.x + box.w))
                mapOf("android:layout_marginEnd" to "${gap}dp")
            } else emptyMap()

            renderSimpleView(box, "$indent    ", extraAttrs)
            context.appendLine()
        }
        context.append("$indent</LinearLayout>")
    }

    private fun renderRadioGroup(boxes: List<ScaledBox>, orientation: String, indent: String) {
        val groupId = context.nextId("radio_group")

        val radios = boxes.mapIndexed { index, box ->
            val parsedAttrs = FuzzyAttributeParser.parse(annotations[box], "RadioButton")

            val requestedId = parsedAttrs["android:id"]?.substringAfterLast('/')
            val id = context.resolveId(requestedId, box.label)

            val extraAttrs = if (orientation == "horizontal" && index < boxes.lastIndex) {
                val gap = maxOf(0, boxes[index + 1].x - (box.x + box.w))
                mapOf("android:layout_marginEnd" to "${gap}dp")
            } else emptyMap()

            val isChecked = box.label == "radio_button_checked" ||
                    parsedAttrs["android:checked"]?.equals("true", ignoreCase = true) == true

            object { val box = box; val attrs = parsedAttrs; val id = id; val extra = extraAttrs; val checked = isChecked }
        }

        val checkedId = radios.firstOrNull { it.checked }?.id

        context.appendLine("$indent<RadioGroup")
        context.appendLine("$indent    android:id=\"@+id/${groupId.escapeXmlAttr()}\"")
        context.appendLine("$indent    android:layout_width=\"match_parent\"")
        context.appendLine("$indent    android:layout_height=\"wrap_content\"")
        context.appendLine("$indent    android:orientation=\"${orientation.escapeXmlAttr()}\"")
        if (checkedId != null) {
            context.appendLine("$indent    android:checkedButton=\"@id/${checkedId.escapeXmlAttr()}\"")
        }
        context.appendLine("$indent>")

        radios.forEach { radio ->
            val safeAttrs = radio.attrs.toMutableMap()
            if (radio.id == checkedId) {
                safeAttrs["android:checked"] = "true"
            } else {
                safeAttrs["android:checked"] = "false"
            }

            renderSimpleView(
                box = radio.box,
                indent = "$indent    ",
                extraAttrs = radio.extra,
                idOverride = radio.id,
                parsedAttrsOverride = safeAttrs
            )
            context.appendLine()
        }
        context.append("$indent</RadioGroup>")
    }
}
