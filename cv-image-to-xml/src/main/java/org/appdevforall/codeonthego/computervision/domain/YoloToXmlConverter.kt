package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object YoloToXmlConverter {

    private const val MIN_H_TEXT = 24
    private const val MIN_W_ANY = 8
    private const val MIN_H_ANY = 8
    private const val DEFAULT_SPACING_DP = 16

    // --- Thresholds for relationship detection (in DP) ---
    private const val HORIZONTAL_ALIGN_THRESHOLD = 20
    private const val VERTICAL_ALIGN_THRESHOLD = 20
    private const val RADIO_GROUP_GAP_THRESHOLD = 24 // Max *vertical* gap

    private data class ScaledBox(
        val label: String,
        val text: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val centerX: Int,
        val centerY: Int
    )

    fun generateXmlLayout(
        detections: List<DetectionResult>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int,
        wrapInScroll: Boolean = true
    ): String {
        val scaledBoxes = detections.map {
            scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight)
        }
        val sortedBoxes = scaledBoxes.sortedWith(compareBy({ it.y }, { it.x }))
        return buildXml(sortedBoxes, targetDpWidth, targetDpHeight, wrapInScroll)
    }

    private fun scaleDetection(
        detection: DetectionResult,
        sourceWidth: Int,
        sourceHeight: Int,
        targetW: Int,
        targetH: Int
    ): ScaledBox {
        val rect = detection.boundingBox
        val normCx = rect.centerX() / sourceWidth
        val normCy = rect.centerY() / sourceHeight
        val normW = rect.width() / sourceWidth
        val normH = rect.height() / sourceHeight

        val ww = normW * targetW
        val hh = normH * targetH
        val x0 = (normCx - normW / 2.0) * targetW
        val y0 = (normCy - normH / 2.0) * targetH

        val x = max(0, x0.roundToInt())
        val y = max(0, y0.roundToInt())
        var width = max(MIN_W_ANY, ww.roundToInt())
        var height = max(MIN_H_ANY, hh.roundToInt())
        val centerX = x + (width / 2)
        val centerY = y + (height / 2)

        if (x + width > targetW) {
            width = max(MIN_W_ANY, targetW - x)
        }

        return ScaledBox(detection.label, detection.text, x, y, width, height, centerX, centerY)
    }

    private fun viewTagFor(label: String): String {
        return when (label) {
            "text" -> "TextView"
            "button" -> "Button"
            "image_placeholder", "icon" -> "ImageView"
            "checkbox_unchecked", "checkbox_checked" -> "CheckBox"
            "radio_unchecked", "radio_checked" -> "RadioButton"
            "switch_off", "switch_on" -> "Switch"
            "text_entry_box" -> "EditText"
            "dropdown" -> "Spinner"
            "card" -> "androidx.cardview.widget.CardView"
            "slider" -> "SeekBar"
            "progress_bar" -> "ProgressBar"
            "toolbar" -> "androidx.appcompat.widget.Toolbar"
            else -> "View"
        }
    }

    private fun isRadioButton(box: ScaledBox) = viewTagFor(box.label) == "RadioButton"

    private fun areHorizontallyAligned(box1: ScaledBox, box2: ScaledBox): Boolean {
        return abs(box1.centerY - box2.centerY) < HORIZONTAL_ALIGN_THRESHOLD
    }

    private fun areVerticallyAligned(box1: ScaledBox, box2: ScaledBox): Boolean {
        return abs(box1.centerX - box2.centerX) < VERTICAL_ALIGN_THRESHOLD
    }

    // --- REFACTORED XML BUILDING LOGIC ---

    private fun buildXml(boxes: List<ScaledBox>, targetDpWidth: Int, targetDpHeight: Int, wrapInScroll: Boolean): String {
        val xml = StringBuilder()
        val maxBottom = boxes.maxOfOrNull { it.y + it.h } ?: 0
        val needScroll = wrapInScroll && maxBottom > targetDpHeight

        val namespaces = """
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            xmlns:tools="http://schemas.android.com/tools"
        """.trimIndent()

        xml.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")

        // --- Root Container Setup ---
        if (needScroll) {
            xml.appendLine("<ScrollView $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:fillViewport=\"true\">")
            xml.appendLine("    <LinearLayout android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        } else {
            xml.appendLine("<LinearLayout $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        }
        xml.appendLine()

        val counters = mutableMapOf<String, Int>()
        val processingList = boxes.toMutableList()

        while (processingList.isNotEmpty()) {
            val currentBox = processingList.removeAt(0)

            // --- 1. Check for RadioGroup (Vertical OR Horizontal) ---
            if (isRadioButton(currentBox)) {
                val radioGroup = mutableListOf(currentBox)
                var groupOrientation: String? = null // null, "vertical", "horizontal"

                // Check for a following radio button
                if (processingList.isNotEmpty() && isRadioButton(processingList.first())) {
                    val nextButton = processingList.first()

                    // Check for VERTICAL group
                    val verticalGap = nextButton.y - (currentBox.y + currentBox.h)
                    if (areVerticallyAligned(currentBox, nextButton) && verticalGap in 0..RADIO_GROUP_GAP_THRESHOLD) {
                        groupOrientation = "vertical"
                        radioGroup.add(processingList.removeAt(0)) // Add the second button

                        // Keep adding more vertical buttons
                        while (processingList.isNotEmpty() &&
                            isRadioButton(processingList.first()) &&
                            areVerticallyAligned(currentBox, processingList.first())) {

                            val nextGap = processingList.first().y - (radioGroup.last().y + radioGroup.last().h)
                            if (nextGap in 0..RADIO_GROUP_GAP_THRESHOLD) {
                                radioGroup.add(processingList.removeAt(0))
                            } else {
                                break // Gap is too large
                            }
                        }

                        // Check for HORIZONTAL group
                    } else if (areHorizontallyAligned(currentBox, nextButton)) {
                        groupOrientation = "horizontal"
                        radioGroup.add(processingList.removeAt(0)) // Add the second button

                        // Keep adding more horizontal buttons
                        while (processingList.isNotEmpty() &&
                            isRadioButton(processingList.first()) &&
                            areHorizontallyAligned(currentBox, processingList.first())) {

                            radioGroup.add(processingList.removeAt(0))
                        }
                    }
                }

                if (radioGroup.size > 1 && groupOrientation != null) {
                    appendRadioGroup(xml, radioGroup, counters, targetDpWidth, groupOrientation)
                    continue // Skip to next loop iteration
                }
                // If only 1 radio, or orientation not found, fall through to be handled as a simple view
            }

            // --- 2. Check for Horizontal Layout (that is NOT a radio group) ---
            val horizontalGroup = mutableListOf(currentBox)

            // Look ahead for more horizontally aligned views
            // **CRITICAL FIX**: Added '!isRadioButton(...)' to prevent stealing from a radio group
            while (processingList.isNotEmpty() &&
                !isRadioButton(processingList.first()) && // Don't group radio buttons
                areHorizontallyAligned(currentBox, processingList.first())) {

                horizontalGroup.add(processingList.removeAt(0))
            }

            if (horizontalGroup.size > 1) {
                appendHorizontalLayout(xml, horizontalGroup, counters, targetDpWidth)
                continue // Skip to next loop iteration
            }

            // --- 3. Handle as Simple, Single View ---
            // (Fell through from RadioButton check OR horizontal group check)
            appendSimpleView(xml, currentBox, counters, targetDpWidth, "        ")
            xml.appendLine() // Add blank line after simple view
        }


        // --- Closing Tags ---
        if (needScroll) {
            xml.appendLine("    </LinearLayout>")
            xml.appendLine("</ScrollView>")
        } else {
            xml.appendLine("</LinearLayout>")
        }

        return xml.toString()
    }

    /**
     * Appends a <RadioGroup> containing multiple <RadioButton> views.
     * **FIXED**: Now handles orientation.
     */
    private fun appendRadioGroup(
        xml: StringBuilder,
        group: List<ScaledBox>,
        counters: MutableMap<String, Int>,
        targetDpWidth: Int,
        orientation: String
    ) {
        val first = group.first()
        val indent = "        "
        xml.appendLine("$indent<RadioGroup")
        xml.appendLine("$indent    android:layout_width=\"wrap_content\"")
        xml.appendLine("$indent    android:layout_height=\"wrap_content\"")
        xml.appendLine("$indent    android:orientation=\"$orientation\"") // Use dynamic orientation

        val gravityAttr = getGravityAttr(first, targetDpWidth)
        if (gravityAttr != null) {
            xml.appendLine("$indent    android:layout_gravity=\"$gravityAttr\"")
        }
        xml.appendLine("$indent    android:layout_marginTop=\"${DEFAULT_SPACING_DP}dp\">")
        xml.appendLine()

        // Add each RadioButton *inside* the group
        group.forEachIndexed { index, box ->
            // Apply margin to separate items if horizontal
            val horizontalMargin = if (orientation == "horizontal" && index > 0) {
                (box.x - (group[index - 1].x + group[index - 1].w))
            } else 0

            appendSimpleView(
                xml,
                box,
                counters,
                targetDpWidth,
                "$indent    ",
                isChildView = true,
                extraMarginStart = max(0, horizontalMargin)
            )
        }

        xml.appendLine("$indent</RadioGroup>")
        xml.appendLine()
    }

    /**
     * Appends a horizontal <LinearLayout> containing multiple views.
     */
    private fun appendHorizontalLayout(xml: StringBuilder, group: List<ScaledBox>, counters: MutableMap<String, Int>, targetDpWidth: Int) {
        val indent = "        "
        xml.appendLine("$indent<LinearLayout")
        xml.appendLine("$indent    android:layout_width=\"match_parent\"")
        xml.appendLine("$indent    android:layout_height=\"wrap_content\"")
        xml.appendLine("$indent    android:orientation=\"horizontal\"")
        xml.appendLine("$indent    android:gravity=\"center_vertical\"")
        xml.appendLine("$indent    android:layout_marginTop=\"${DEFAULT_SPACING_DP}dp\">")
        xml.appendLine()

        group.forEachIndexed { index, box ->
            val horizontalMargin = if (index > 0) (box.x - (group[index - 1].x + group[index - 1].w)) else 0

            appendSimpleView(
                xml,
                box,
                counters,
                targetDpWidth,
                "$indent    ",
                isChildView = true,
                extraMarginStart = max(0, horizontalMargin)
            )
        }

        xml.appendLine("$indent</LinearLayout>")
        xml.appendLine()
    }

    /**
     * Appends a single, simple view tag (e.g., <TextView ... />).
     */
    private fun appendSimpleView(
        xml: StringBuilder,
        box: ScaledBox,
        counters: MutableMap<String, Int>,
        targetDpWidth: Int,
        indent: String,
        isChildView: Boolean = false,
        extraMarginStart: Int = 0
    ) {
        val label = box.label
        val tag = viewTagFor(label)
        val count = counters.getOrPut(label) { 0 }.also { counters[label] = it + 1 }
        val id = "${label.replace(Regex("[^a-zA-Z0-9_]"), "_")}_$count"

        val isTextBased = tag in listOf("TextView", "Button", "EditText", "CheckBox", "RadioButton", "Switch")
        val isWide = box.w > (targetDpWidth * 0.8) && !isChildView

        val widthAttr = when {
            isWide -> "match_parent"
            isTextBased -> "wrap_content"
            isChildView -> "wrap_content" // Use weights for horizontal groups eventually
            else -> "${box.w}dp"
        }

        val heightAttr = when {
            isTextBased -> "wrap_content"
            else -> "${max(MIN_H_TEXT, box.h)}dp"
        }

        val gravityAttr = getGravityAttr(box, targetDpWidth)

        xml.appendLine("$indent<$tag")
        xml.appendLine("$indent    android:id=\"@+id/$id\"")
        xml.appendLine("$indent    android:layout_width=\"$widthAttr\"")
        xml.appendLine("$indent    android:layout_height=\"$heightAttr\"")

        if (!isChildView) {
            if (gravityAttr != null) {
                xml.appendLine("$indent    android:layout_gravity=\"$gravityAttr\"")
            }
            xml.appendLine("$indent    android:layout_marginTop=\"${DEFAULT_SPACING_DP}dp\"")
        } else if (extraMarginStart > 0) {
            xml.appendLine("$indent    android:layout_marginStart=\"${extraMarginStart}dp\"")
        }

        // --- Component Specific Attributes ---
        when (tag) {
            "TextView", "Button" -> {
                val viewText = if (box.text.isNotEmpty()) box.text else box.label
                xml.appendLine("$indent    android:text=\"$viewText\"")
                if (tag == "TextView") {
                    xml.appendLine("$indent    android:textSize=\"16sp\"")
                }
                xml.appendLine("$indent    tools:ignore=\"HardcodedText\"")
            }
            "EditText" -> {
                val hintText = if (box.text.isNotEmpty()) box.text else "Enter text..."
                xml.appendLine("$indent    android:hint=\"$hintText\"")
                xml.appendLine("$indent    android:inputType=\"text\"")
                xml.appendLine("$indent    tools:ignore=\"HardcodedText\"")
            }
            "ImageView" -> {
                xml.appendLine("$indent    android:contentDescription=\"$label\"")
                xml.appendLine("$indent    android:scaleType=\"centerCrop\"")
                xml.appendLine("$indent    android:background=\"#E0E0E0\"")
            }
            "CheckBox", "RadioButton", "Switch" -> {
                val viewText = if (box.text.isNotEmpty()) box.text else box.label
                xml.appendLine("$indent    android:text=\"$viewText\"")
                if (label.contains("_checked") || label.contains("_on")) {
                    xml.appendLine("$indent    android:checked=\"true\"")
                }
            }
            "androidx.cardview.widget.CardView" -> {
                xml.appendLine("$indent    app:cardElevation=\"4dp\"")
                xml.appendLine("$indent    app:cardCornerRadius=\"8dp\"")
            }
        }

        xml.appendLine("$indent/>")
    }

    /**
     * Calculates the layout_gravity attribute for a simple view.
     */
    private fun getGravityAttr(box: ScaledBox, targetDpWidth: Int): String? {
        val isWide = box.w > (targetDpWidth * 0.8)
        if (isWide) return null

        val relativeCenter = box.centerX.toFloat() / targetDpWidth.toFloat()
        return when {
            relativeCenter < 0.35 -> "start"
            relativeCenter > 0.65 -> "end"
            else -> "center_horizontal"
        }
    }
}