package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.FuzzyAttributeParser.AttributeKey
import kotlin.text.substringAfterLast

sealed class AndroidWidget(
    protected val box: ScaledBox,
    protected val parsedAttrs: Map<String, String>
) {
    abstract val tag: String

    protected open fun fallbackIdLabel(): String = box.label

    protected abstract fun specificAttributes(): Map<String, String>
    protected open fun processAttributes(context: XmlContext, id: String, attrs: Map<String, String>): Map<String, String> {
        return attrs.mapValues { it.value.escapeXmlAttr() }
    }

    fun render(
        context: XmlContext,
        indent: String,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null
    ) {
        val requestedId = idOverride ?: parsedAttrs[AttributeKey.ID.xmlName]?.substringAfterLast('/')
        val id = context.resolveId(requestedId, fallbackIdLabel())
        val width = parsedAttrs[AttributeKey.WIDTH.xmlName] ?: extraAttrs[AttributeKey.WIDTH.xmlName] ?: "wrap_content"
        val height = parsedAttrs[AttributeKey.HEIGHT.xmlName] ?: extraAttrs[AttributeKey.HEIGHT.xmlName] ?: "wrap_content"

        val finalAttrs = mutableMapOf(
            AttributeKey.ID.xmlName to "@+id/${id.escapeXmlAttr()}",
            AttributeKey.WIDTH.xmlName to width.escapeXmlAttr(),
            AttributeKey.HEIGHT.xmlName to height.escapeXmlAttr()
        )

        specificAttributes().forEach { (k, v) -> finalAttrs[k] = v.escapeXmlAttr() }

        val mergedAttrs = parsedAttrs + extraAttrs
        val processedAttrs = processAttributes(context, id, mergedAttrs)

        processedAttrs.forEach { (key, value) ->
            finalAttrs.putIfAbsent(key, value)
        }

        context.append("$indent<$tag\n")
        finalAttrs.forEach { (key, value) ->
            context.append("$indent    $key=\"$value\"\n")
        }
        context.append("$indent/>")
    }

    companion object {
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
            "text" -> "TextView"
            "button" -> "Button"
            "image_placeholder", "icon" -> "ImageView"
            "checkbox_unchecked", "checkbox_checked" -> "CheckBox"
            "radio_button_unchecked", "radio_button_checked" -> "RadioButton"
            "switch_off", "switch_on" -> "Switch"
            "text_entry_box" -> "EditText"
            "dropdown" -> "Spinner"
            "slider" -> "SeekBar"
            else -> "View"
        }
    }
}

class SpinnerWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "Spinner"
    override fun fallbackIdLabel(): String = "spinner"
    override fun specificAttributes() = emptyMap<String, String>()

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
                .takeIf { it.isNotEmpty() }
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

    private fun String.toSpinnerEntries(): List<String> {
        return removeTrailingDropdownGlyph()
            .split(Regex("\\s*[,;|/\\n]+\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun String.removeTrailingDropdownGlyph(): String {
        return trim()
            .replace(Regex("\\s*[▼▽▾▿⌄˅∨]$|\\s+[vV]$"), "")
            .trim()
    }

    private fun String.isMeaningfulDropdownText(): Boolean {
        val cleaned = removeTrailingDropdownGlyph()
        return cleaned.isNotBlank() && !cleaned.equals("dropdown", ignoreCase = true)
    }
}

class TextBasedWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>,
    override val tag: String
) : AndroidWidget(box, parsedAttrs) {
    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val widgetTags = setOf("Switch", "CheckBox", "RadioButton")
        val rawViewText = parsedAttrs[AttributeKey.TEXT.xmlName]
            ?: box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: if (tag in widgetTags) tag else box.label

        attrs[AttributeKey.TEXT.xmlName] = rawViewText
        attrs["tools:ignore"] = "HardcodedText"

        if (tag == "TextView") {
            attrs[AttributeKey.TEXT_SIZE.xmlName] = parsedAttrs[AttributeKey.TEXT_SIZE.xmlName] ?: "16sp"
        }
        if (box.label.contains("_checked") || box.label.contains("_on")) {
            attrs[AttributeKey.CHECKED.xmlName] = parsedAttrs[AttributeKey.CHECKED.xmlName] ?: "true"
        }
        return attrs
    }
}

class CheckBoxWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "CheckBox"

    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()

        val rawViewText = box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: parsedAttrs[AttributeKey.TEXT.xmlName]
            ?: "CheckBox"

        attrs[AttributeKey.TEXT.xmlName] = rawViewText
        attrs["tools:ignore"] = "HardcodedText"

        if (box.label.contains("_checked")) {
            attrs[AttributeKey.CHECKED.xmlName] = parsedAttrs[AttributeKey.CHECKED.xmlName] ?: "true"
        }
        return attrs
    }
}

class SwitchWidget(
    box: ScaledBox,
    parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "Switch"

    override fun fallbackIdLabel(): String = "switch"

    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val switchText = parsedAttrs[AttributeKey.TEXT.xmlName] ?: box.text.trim().takeIf { it.isNotEmpty() && it != box.label } ?: "Switch"

        attrs[AttributeKey.TEXT.xmlName] = switchText
        attrs["tools:ignore"] = "HardcodedText"

        if (box.label.contains("_on")) {
            attrs[AttributeKey.CHECKED.xmlName] = parsedAttrs[AttributeKey.CHECKED.xmlName] ?: "true"
        }

        return attrs
    }
}

class InputWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "EditText"
    override fun specificAttributes(): Map<String, String> = mapOf(
        AttributeKey.HINT.xmlName to (parsedAttrs[AttributeKey.HINT.xmlName] ?: box.text.ifEmpty { "Enter text..." }),
        AttributeKey.INPUT_TYPE.xmlName to (parsedAttrs[AttributeKey.INPUT_TYPE.xmlName] ?: "text"),
        "tools:ignore" to "HardcodedText"
    )
}

class ImageWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "ImageView"
    override fun specificAttributes(): Map<String, String> = mapOf(
        AttributeKey.CONTENT_DESCRIPTION.xmlName to (parsedAttrs[AttributeKey.CONTENT_DESCRIPTION.xmlName] ?: box.label),
    )
}

class GenericWidget(box: ScaledBox, parsedAttrs: Map<String, String>, override val tag: String) : AndroidWidget(box, parsedAttrs) {
    override fun specificAttributes() = emptyMap<String, String>()
}
