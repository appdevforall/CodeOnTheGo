package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.utils.extractOcrEntries

sealed class AndroidWidget(
    protected open val box: ScaledBox?,
    protected val parsedAttrs: Map<String, String>
) {
    abstract val tag: String
    var idOverride: String? = null
    var extraAttrs: Map<String, String> = emptyMap()

    protected open fun fallbackIdLabel() = box?.label ?: tag.lowercase()
    protected open fun defaultWidth() = AndroidConstants.WRAP_CONTENT
    protected open fun defaultHeight() = AndroidConstants.WRAP_CONTENT
    protected open fun getChildren(): List<AndroidWidget> = emptyList()

    protected abstract fun specificAttributes(): Map<String, String>

    protected open fun processAttributes(context: XmlContext, id: String, attrs: Map<String, String>): Map<String, String> {
        return attrs.mapValues { it.value.escapeXmlAttr() }
    }

    fun render(context: XmlContext, indent: String) {
        val resolvedId = resolveWidgetId(context)
        val finalAttributes = assembleAttributes(context, resolvedId)
        writeXml(context, indent, finalAttributes)
    }

    protected open fun resolveWidgetId(context: XmlContext): String {
        val requestedId = idOverride ?: parsedAttrs[AttributeKey.ID.xmlName]?.substringAfterLast('/')
        return context.resolveId(requestedId, fallbackIdLabel())
    }

    private fun assembleAttributes(context: XmlContext, resolvedId: String): Map<String, String> {
        val width = parsedAttrs[AttributeKey.WIDTH.xmlName] ?: extraAttrs[AttributeKey.WIDTH.xmlName] ?: defaultWidth()
        val height = parsedAttrs[AttributeKey.HEIGHT.xmlName] ?: extraAttrs[AttributeKey.HEIGHT.xmlName] ?: defaultHeight()

        val assembledAttrs = mutableMapOf(
            AttributeKey.ID.xmlName to "@+id/${resolvedId.escapeXmlAttr()}",
            AttributeKey.WIDTH.xmlName to width.escapeXmlAttr(),
            AttributeKey.HEIGHT.xmlName to height.escapeXmlAttr()
        )

        specificAttributes().forEach { (k, v) -> assembledAttrs[k] = v.escapeXmlAttr() }

        val mergedAttrs = parsedAttrs + extraAttrs
        val processedAttrs = processAttributes(context, resolvedId, mergedAttrs)

        processedAttrs.forEach { (key, value) ->
            assembledAttrs.putIfAbsent(key, value)
        }

        return assembledAttrs
    }

    private fun writeXml(context: XmlContext, indent: String, attributes: Map<String, String>) {
        context.append("$indent<$tag\n")

        attributes.forEach { (key, value) ->
            context.append("$indent    $key=\"$value\"\n")
        }

        val childWidgets = getChildren()
        if (childWidgets.isEmpty()) {
            context.append("$indent/>")
        } else {
            context.append("$indent>\n")
            childWidgets.forEach { child ->
                child.render(context, "$indent    ")
                context.appendLine()
            }
            context.append("$indent</$tag>")
        }
    }

    companion object {
        private val nonAlphanumericRegex = Regex("[^a-z0-9_]")
        private val multipleUnderscoresRegex = Regex("_+")

        fun create(box: ScaledBox, parsedAttrs: Map<String, String>): AndroidWidget {
            return when (box.label) {
                "text", "button", "radio_button_unchecked", "radio_button_checked" ->
                    TextBasedWidget(box, parsedAttrs, getTagFor(box.label))
                "checkbox_unchecked", "checkbox_checked" -> CheckBoxWidget(box, parsedAttrs)
                "switch_off", "switch_on" -> SwitchWidget(box, parsedAttrs)
                "text_entry_box" -> InputWidget(box, parsedAttrs)
                "image_placeholder", "icon" -> ImageWidget(box, parsedAttrs)
                "dropdown" -> SpinnerWidget(box, parsedAttrs)
                else -> GenericWidget(box, parsedAttrs, getTagFor(box.label))
            }
        }

        fun getTagFor(label: String): String = when (label) {
            "text" -> AndroidWidgetTags.TEXT_VIEW
            "button" -> AndroidWidgetTags.BUTTON
            "image_placeholder", "icon" -> AndroidWidgetTags.IMAGE_VIEW
            "checkbox_unchecked", "checkbox_checked" -> AndroidWidgetTags.CHECK_BOX
            "radio_button_unchecked", "radio_button_checked" -> AndroidWidgetTags.RADIO_BUTTON
            "switch_off", "switch_on" -> AndroidWidgetTags.SWITCH
            "text_entry_box" -> AndroidWidgetTags.EDIT_TEXT
            "dropdown" -> AndroidWidgetTags.SPINNER
            "slider" -> AndroidWidgetTags.SEEK_BAR
            else -> AndroidWidgetTags.VIEW
        }

        internal fun sanitizeResourceName(raw: String): String {
            return raw
                .lowercase()
                .replace('-', '_')
                .replace(nonAlphanumericRegex, "_")
                .replace(multipleUnderscoresRegex, "_")
                .trim('_')
        }
    }
}

class SpinnerWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    companion object {
        private val placeholderEntries = setOf("year", "month", "day", "select", "choose", "dropdown")
    }

    override val tag = AndroidWidgetTags.SPINNER
    override fun fallbackIdLabel(): String {
        val normalizedLabel = sanitizeResourceName(box.text.normalizedDropdownLabel())
        return normalizedLabel.takeIf { it.isNotBlank() }?.let { "dd_$it" } ?: "spinner"
    }
    override fun specificAttributes() = emptyMap<String, String>()

    override fun resolveWidgetId(context: XmlContext): String {
        val requestedId = idOverride ?: parsedAttrs[AttributeKey.ID.xmlName]?.substringAfterLast('/')
        if (requestedId != null) return context.resolveId(requestedId, fallbackIdLabel())

        val derivedId = fallbackIdLabel()
        return derivedId
            .takeUnless { it == "spinner" }
            ?.let { context.resolveId(it, "spinner") }
            ?: context.nextId("spinner")
    }

    override fun processAttributes(context: XmlContext, id: String, attrs: Map<String, String>): Map<String, String> {
        val processed = mutableMapOf<String, String>()
        val rawEntries = attrs[AttributeKey.ENTRIES.xmlName]
            ?: attrs[AttributeKey.TEXT.xmlName]
            ?: box.text.takeIf { it.isMeaningfulDropdownText() }

        when {
            rawEntries == null -> Unit
            rawEntries.trimStart().startsWith("@") -> {
                processed[AttributeKey.ENTRIES.xmlName] = rawEntries.trim().escapeXmlAttr()
            }
            else -> rawEntries
                .toSpinnerEntries()
                .takeIf { items -> items.isNotEmpty() && !items.isSinglePlaceholderEntry() }
                ?.let { items ->
                    val arrayName = "${id}_array"
                    context.stringArrays[arrayName] = items
                    processed[AttributeKey.ENTRIES.xmlName] = "@array/$arrayName"
                }
        }

        attrs.forEach { (key, value) ->
            when {
                key == AttributeKey.ENTRIES.xmlName || key == AttributeKey.TEXT.xmlName -> Unit
                else -> processed[key] = value.escapeXmlAttr()
            }
        }
        return processed
    }

    private fun List<String>.isSinglePlaceholderEntry(): Boolean {
        if (size != 1) return false
        return first().normalizedDropdownLabel().lowercase() in placeholderEntries
    }

    private fun String.toSpinnerEntries(): List<String> {
        return this.removeTrailingDropdownGlyph().extractOcrEntries()
    }

    private fun String.removeTrailingDropdownGlyph(): String {
        return trim()
            .replace(Regex("\\s*[▼▽▾▿⌄˅∨]$|\\s+[vV]$"), "")
            .trim()
    }

    private fun String.removeLeadingDropdownHint(): String {
        return trim()
            .replace(Regex("^[vV]\\s+"), "")
            .trim()
    }

    private fun String.normalizedDropdownLabel(): String {
        return removeTrailingDropdownGlyph()
            .removeLeadingDropdownHint()
            .trim()
    }

    private fun String.isMeaningfulDropdownText(): Boolean {
        val cleaned = normalizedDropdownLabel()
        return cleaned.isNotBlank() && !cleaned.equals("dropdown", ignoreCase = true)
    }
}

class TextBasedWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>, override val tag: String
) : AndroidWidget(box, parsedAttrs) {
    private val widgetTags = setOf(AndroidWidgetTags.SWITCH, AndroidWidgetTags.CHECK_BOX, AndroidWidgetTags.RADIO_BUTTON)

    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val rawViewText = parsedAttrs[AttributeKey.TEXT.xmlName]
            ?: box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: if (tag in widgetTags) tag else box.label

        attrs[AttributeKey.TEXT.xmlName] = rawViewText
        attrs["tools:ignore"] = "HardcodedText"

        if (tag == AndroidWidgetTags.TEXT_VIEW || tag in widgetTags) {
            attrs[AttributeKey.TEXT_SIZE.xmlName] = parsedAttrs[AttributeKey.TEXT_SIZE.xmlName] ?: AndroidConstants.DEFAULT_TEXT_SIZE
        }
        if (box.label.contains("_checked") || box.label.contains("_on")) {
            attrs[AttributeKey.CHECKED.xmlName] = parsedAttrs[AttributeKey.CHECKED.xmlName] ?: AndroidConstants.TRUE
        }
        return attrs
    }

    override fun fallbackIdLabel(): String {
        return if (tag == AndroidWidgetTags.RADIO_BUTTON) "radio_button" else super.fallbackIdLabel()
    }
}

class CheckBoxWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = AndroidWidgetTags.CHECK_BOX

    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val rawViewText = box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: parsedAttrs[AttributeKey.TEXT.xmlName]
            ?: AndroidWidgetTags.CHECK_BOX

        attrs[AttributeKey.TEXT.xmlName] = rawViewText
        attrs["tools:ignore"] = "HardcodedText"

        if (box.label.contains("_checked")) {
            attrs[AttributeKey.CHECKED.xmlName] = parsedAttrs[AttributeKey.CHECKED.xmlName] ?: AndroidConstants.TRUE
        }
        return attrs
    }

    override fun fallbackIdLabel(): String = "checkbox"
}

class SwitchWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = AndroidWidgetTags.SWITCH
    override fun fallbackIdLabel(): String = "switch"

    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val switchText = parsedAttrs[AttributeKey.TEXT.xmlName] ?: box.text.trim().takeIf { it.isNotEmpty() && it != box.label } ?: AndroidWidgetTags.SWITCH

        attrs[AttributeKey.TEXT.xmlName] = switchText
        attrs["tools:ignore"] = "HardcodedText"

        if (box.label.contains("_on")) {
            attrs[AttributeKey.CHECKED.xmlName] = parsedAttrs[AttributeKey.CHECKED.xmlName] ?: AndroidConstants.TRUE
        }
        return attrs
    }
}

class InputWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = AndroidWidgetTags.EDIT_TEXT

    override fun specificAttributes(): Map<String, String> {
        val resolvedHint = parsedAttrs[AttributeKey.HINT.xmlName] ?: box.text.ifEmpty { "Enter text..." }
        val resolvedInputType = parsedAttrs[AttributeKey.INPUT_TYPE.xmlName] ?: "text"

        return mapOf(
            AttributeKey.HINT.xmlName to resolvedHint,
            AttributeKey.INPUT_TYPE.xmlName to resolvedInputType,
            "tools:ignore" to "HardcodedText"
        )
    }
}

class ImageWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = AndroidWidgetTags.IMAGE_VIEW
    override fun specificAttributes(): Map<String, String> = mapOf(
        AttributeKey.CONTENT_DESCRIPTION.xmlName to (parsedAttrs[AttributeKey.CONTENT_DESCRIPTION.xmlName] ?: box.label),
    )
}

class GenericWidget(
    override val box: ScaledBox, parsedAttrs: Map<String, String>, override val tag: String
) : AndroidWidget(box, parsedAttrs) {
    override fun specificAttributes() = emptyMap<String, String>()
}

abstract class AndroidViewGroup(
    parsedAttrs: Map<String, String>,
    protected val childWidgets: List<AndroidWidget>
) : AndroidWidget(null, parsedAttrs) {
    override fun getChildren() = childWidgets
}

class HorizontalRowWidget(
    childWidgets: List<AndroidWidget>
) : AndroidViewGroup(emptyMap(), childWidgets) {
    override val tag = AndroidWidgetTags.LINEAR_LAYOUT
    override fun fallbackIdLabel() = "linear_layout"
    override fun defaultWidth() = AndroidConstants.MATCH_PARENT
    override fun specificAttributes() = mapOf(
        "android:orientation" to AndroidConstants.ORIENTATION_HORIZONTAL,
        "android:baselineAligned" to AndroidConstants.FALSE
    )
}

class RadioGroupWidget(
    parsedAttrs: Map<String, String>,
    childWidgets: List<AndroidWidget>,
    private val orientation: String,
    private val checkedId: String?
) : AndroidViewGroup(parsedAttrs, childWidgets) {
    override val tag = AndroidWidgetTags.RADIO_GROUP
    override fun fallbackIdLabel() = "radio_group"
    override fun defaultWidth() = AndroidConstants.MATCH_PARENT

    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf("android:orientation" to orientation)
        if (checkedId != null) {
            attrs["android:checkedButton"] = "@id/$checkedId"
        }
        return attrs
    }
}
