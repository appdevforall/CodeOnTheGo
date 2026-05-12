package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser

class WidgetFactory(
    private val context: XmlContext,
    private val annotations: Map<ScaledBox, String>,
    private val selectedImageOverrides: Map<ScaledBox, String> = emptyMap()
) {
    private val checkboxGroupIdPattern = Regex("^cb_group_\\d+$")
    private val radioChildGroupIdPatterns = listOf(
        Regex("^rb_group(?:_\\d+)?(?:_|$).*"),
        Regex("^radio_group(?:_\\d+)?(?:_|$).*")
    )

    fun createWidgets(item: LayoutItem): List<AndroidWidget> = when (item) {
        is LayoutItem.SimpleView -> createWidgetsForBox(item.box)
        is LayoutItem.HorizontalRow -> createHorizontalRow(item)
        is LayoutItem.RadioGroup -> createRadioGroup(item)
        is LayoutItem.CheckboxGroup -> createCheckboxGroup(item)
    }

    private fun createHorizontalRow(item: LayoutItem.HorizontalRow): List<AndroidWidget> {
        val children = item.row.flatMapIndexed { index, box ->
            val extraAttrs = getMarginEndForHorizontalGap(item.row, index)
            createWidgetsForBox(box, extraAttrs = extraAttrs)
        }
        return listOf(HorizontalRowWidget(children))
    }

    private fun createWidgetsForBox(
        box: ScaledBox,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null,
        parsedAttrsOverride: Map<String, String>? = null
    ): List<AndroidWidget> {
        val widgets = mutableListOf<AndroidWidget>()

        val dropdownTitle = box.text
            .takeIf { box.label == "dropdown" }
            ?.let(::extractDropdownTitle)

        if (dropdownTitle != null) {
            val titleBox = box.copy(label = "text", text = dropdownTitle)

            val titleAttrs = mapOf(
                AttributeKey.WIDTH.xmlName to AndroidConstants.WRAP_CONTENT,
                AttributeKey.HEIGHT.xmlName to AndroidConstants.WRAP_CONTENT,
                AttributeKey.LAYOUT_MARGIN_BOTTOM.xmlName to "4dp",
                AttributeKey.TEXT.xmlName to dropdownTitle,
                AttributeKey.TEXT_STYLE.xmlName to "bold"
            )
            widgets.add(createSimpleWidget(titleBox, parsedAttrsOverride = titleAttrs))

            val baseParsedAttrs = parsedAttrsOverride?.toMutableMap()
                ?: FuzzyAttributeParser.parse(annotations[box], AndroidWidget.getTagFor(box.label)).toMutableMap()

            val spinnerBox = box.copy(text = "")
            widgets.add(createSimpleWidget(spinnerBox, extraAttrs, idOverride, baseParsedAttrs))

            return widgets
        }

        widgets.add(createSimpleWidget(box, extraAttrs, idOverride, parsedAttrsOverride))
        return widgets
    }

    private fun createRadioGroup(item: LayoutItem.RadioGroup): List<AndroidWidget> {
        val groupAnnotation = item.boxes.firstNotNullOfOrNull { annotations[it] }
        val fullGroupAttrs = FuzzyAttributeParser.parse(groupAnnotation, "RadioGroup")

        val groupId = resolveRadioGroupId(fullGroupAttrs["android:id"]?.substringAfterLast('/'))

        val groupStructuralAttrs = setOf("android:id", "android:layout_width", "android:layout_height", "android:orientation")
        val sharedAttrs = fullGroupAttrs.filterKeys { it !in groupStructuralAttrs }

        var checkedId: String? = null

        val children = item.boxes.mapIndexed { index, box ->
            val parsedAttrs = (sharedAttrs + FuzzyAttributeParser.parse(annotations[box], "RadioButton")).toMutableMap()

            if (parsedAttrs["android:id"] == fullGroupAttrs["android:id"]) {
                parsedAttrs.remove("android:id")
            }

            val requestedId = parsedAttrs["android:id"]?.substringAfterLast('/')
            val childId = if (requestedId != null && radioChildGroupIdPatterns.any { it.matches(requestedId) }) {
                context.nextId("radio_button")
            } else {
                context.resolveId(requestedId, "radio_button")
            }

            val isChecked = box.label == "radio_button_checked" || parsedAttrs["android:checked"]?.equals("true", ignoreCase = true) == true
            if (isChecked) {
                checkedId = childId
                parsedAttrs["android:checked"] = "true"
            } else {
                parsedAttrs["android:checked"] = "false"
            }

            val extraAttrs = if (item.orientation == "horizontal") {
                getMarginEndForHorizontalGap(item.boxes, index)
            } else emptyMap()

            createSimpleWidget(box, parsedAttrsOverride = parsedAttrs, idOverride = childId, extraAttrs = extraAttrs)
        }

        val textStyleAttrs = setOf("android:textColor", "android:textSize", "android:textStyle", "android:fontFamily")
        val groupFinalAttrs = fullGroupAttrs.filterKeys { it !in textStyleAttrs }.toMutableMap()
        groupFinalAttrs["android:id"] = groupId

        return listOf(RadioGroupWidget(groupFinalAttrs, children, item.orientation, checkedId))
    }

    private fun createCheckboxGroup(item: LayoutItem.CheckboxGroup): List<AndroidWidget> {
        val groupAnnotation = item.boxes.firstNotNullOfOrNull { annotations[it] }
        val parsedAttrs = FuzzyAttributeParser.parse(groupAnnotation, "CheckBox")

        val requestedId = parsedAttrs["android:id"]?.substringAfterLast('/')
        val baseId = if (requestedId != null && checkboxGroupIdPattern.matches(requestedId)) {
            context.resolveId(requestedId, "cb_group")
        } else {
            context.nextId("cb_group", initialIndex = 1)
        }

        return item.boxes.mapIndexed { index, box ->
            val suffix = ('a' + index).toString()
            val childId = "${baseId}_$suffix"

            val safeAttrs = parsedAttrs.toMutableMap()
            safeAttrs.remove("android:id")

            val extraAttrs = if (item.orientation == "horizontal") {
                getMarginEndForHorizontalGap(item.boxes, index)
            } else emptyMap()

            createSimpleWidget(box, parsedAttrsOverride = safeAttrs, idOverride = childId, extraAttrs = extraAttrs)
        }
    }

    private fun createSimpleWidget(
        box: ScaledBox,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null,
        parsedAttrsOverride: Map<String, String>? = null
    ): AndroidWidget {
        val tag = AndroidWidget.getTagFor(box.label)
        val parsedAttrs = parsedAttrsOverride?.toMutableMap()
            ?: FuzzyAttributeParser.parse(annotations[box], tag).toMutableMap()

        selectedImageOverrides[box]?.let { drawableReference ->
            parsedAttrs["android:src"] = drawableReference
        }

        return AndroidWidget.create(box, parsedAttrs).apply {
            this.idOverride = idOverride
            this.extraAttrs = extraAttrs
        }
    }

    private fun extractDropdownTitle(rawText: String): String? {
        val cleaned = rawText.trim()
            .replace(Regex("\\s*[▼▽▾▿⌄˅∨]$|\\s+[vV]$"), "")
            .replace(Regex("^[vV]\\s+"), "")
            .trim()

        return cleaned.takeIf { it.isNotBlank() && !it.equals("dropdown", ignoreCase = true) }
    }

    private fun getMarginEndForHorizontalGap(boxes: List<ScaledBox>, currentIndex: Int): Map<String, String> {
        if (currentIndex >= boxes.lastIndex) return emptyMap()
        val currentBox = boxes[currentIndex]
        val nextBox = boxes[currentIndex + 1]
        val gap = maxOf(0, nextBox.x - (currentBox.x + currentBox.w))
        return mapOf("android:layout_marginEnd" to "${gap}dp")
    }

    private fun resolveRadioGroupId(requestedId: String?): String {
        var cleanId = requestedId
        if (requestedId != null) {
            val normalizedId = requestedId.lowercase()
            when {
                normalizedId.startsWith("radio_grou") -> cleanId = "radio_group"
                normalizedId.startsWith("rb_grou") || normalizedId.startsWith("rb_group") -> cleanId = "rb_group"
            }
        }
        return context.resolveId(cleanId, "radio_group")
    }
}
