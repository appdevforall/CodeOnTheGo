package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import kotlin.text.substringAfterLast

sealed class AndroidWidget(
    protected val box: ScaledBox,
    protected val parsedAttrs: Map<String, String>
) {
    abstract val tag: String

    protected open fun fallbackIdLabel(): String = box.label

    protected abstract fun specificAttributes(): Map<String, String>

    fun render(
        context: XmlContext,
        indent: String,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null
    ) {
        val requestedId = idOverride ?: parsedAttrs["android:id"]?.substringAfterLast('/')
        val id = context.resolveId(requestedId, fallbackIdLabel())
        val width = parsedAttrs["android:layout_width"] ?: extraAttrs["android:layout_width"] ?: "wrap_content"
        val height = parsedAttrs["android:layout_height"] ?: extraAttrs["android:layout_height"] ?: "wrap_content"

        val finalAttrs = mutableMapOf(
            "android:id" to "@+id/${id.escapeXmlAttr()}",
            "android:layout_width" to width.escapeXmlAttr(),
            "android:layout_height" to height.escapeXmlAttr()
        )

        specificAttributes().forEach { (k, v) -> finalAttrs[k] = v.escapeXmlAttr() }

        (parsedAttrs + extraAttrs).forEach { (key, value) ->
            finalAttrs.putIfAbsent(key, value.escapeXmlAttr())
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
                "text", "button", "checkbox_unchecked", "checkbox_checked",
                "radio_button_unchecked", "radio_button_checked" ->
                    TextBasedWidget(box, parsedAttrs, getTagFor(box.label))
                "switch_off", "switch_on" -> SwitchWidget(box, parsedAttrs)
                "text_entry_box" -> InputWidget(box, parsedAttrs)
                "image_placeholder", "icon" -> ImageWidget(box, parsedAttrs)
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

class TextBasedWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>,
    override val tag: String
) : AndroidWidget(box, parsedAttrs) {
    override fun specificAttributes(): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val widgetTags = setOf("Switch", "CheckBox", "RadioButton")
        val rawViewText = parsedAttrs["android:text"]
            ?: box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: if (tag in widgetTags) tag else box.label

        attrs["android:text"] = rawViewText
        attrs["tools:ignore"] = "HardcodedText"

        if (tag == "TextView") {
            attrs["android:textSize"] = parsedAttrs["android:textSize"] ?: "16sp"
        }
        if (box.label.contains("_checked") || box.label.contains("_on")) {
            attrs["android:checked"] = parsedAttrs["android:checked"] ?: "true"
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
        val switchText = parsedAttrs["android:text"] ?: box.text.trim().takeIf { it.isNotEmpty() && it != box.label } ?: "Switch"

        attrs["android:text"] = switchText
        attrs["tools:ignore"] = "HardcodedText"

        if (box.label.contains("_on")) {
            attrs["android:checked"] = parsedAttrs["android:checked"] ?: "true"
        }

        return attrs
    }
}

class InputWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "EditText"
    override fun specificAttributes(): Map<String, String> = mapOf(
        "android:hint" to (parsedAttrs["android:hint"] ?: box.text.ifEmpty { "Enter text..." }),
        "android:inputType" to (parsedAttrs["android:inputType"] ?: "text"),
        "tools:ignore" to "HardcodedText"
    )
}

class ImageWidget(
    box: ScaledBox, parsedAttrs: Map<String, String>
) : AndroidWidget(box, parsedAttrs) {
    override val tag = "ImageView"
    override fun specificAttributes(): Map<String, String> = mapOf(
        "android:contentDescription" to (parsedAttrs["android:contentDescription"] ?: box.label),
    )
}

class GenericWidget(box: ScaledBox, parsedAttrs: Map<String, String>, override val tag: String) : AndroidWidget(box, parsedAttrs) {
    override fun specificAttributes() = emptyMap<String, String>()
}
