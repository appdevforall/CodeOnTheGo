package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object YoloToXmlConverter {

    private const val TAG = "YoloToXmlConverter"
    private const val MIN_W_ANY = 8
    private const val MIN_H_ANY = 8

    private data class ScaledBox(
        val label: String, var text: String, val rect: Rect, val detection: DetectionResult,
        val centerX: Int, val centerY: Int
    )

    private fun isTag(text: String): Boolean = text.matches(Regex("^(B-|P-|D-|T-|C-|R-|S-|I-)\\d+$"))

    private fun distance(box1: ScaledBox, box2: ScaledBox): Float {
        val dx = (box1.centerX - box2.centerX).toFloat()
        val dy = (box1.centerY - box2.centerY).toFloat()
        return sqrt(dx.pow(2) + dy.pow(2))
    }

    fun generateXmlLayout(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int,
        wrapInScroll: Boolean = true
    ): String {
        val scaledBoxes = detections.map { scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight) }

        val widgets = scaledBoxes.filter { it.label != "text" && !isTag(it.text) }.toMutableList()
        val texts = scaledBoxes.filter { it.label == "text" && !isTag(it.text) }.toMutableList()
        val tags = scaledBoxes.filter { isTag(it.text) }

        val finalAnnotations = mutableMapOf<DetectionResult, String>()
        val consumedTexts = mutableSetOf<ScaledBox>()

        // Process each widget to find its text and its annotation
        for (widget in widgets) {
            // 1. Find contained text
            texts.firstOrNull { text ->
                !consumedTexts.contains(text) && widget.rect.contains(text.rect)
            }?.let {
                widget.text = it.text
                consumedTexts.add(it)
            }

            // 2. Find the CLOSEST tag to associate its annotation
            tags.minByOrNull { distance(widget, it) }
                ?.let { tag ->
                    annotations[tag.text]?.let { annotationString ->
                        finalAnnotations[widget.detection] = annotationString
                    }
                }
        }

        val finalRenderList = (widgets + texts.filter { !consumedTexts.contains(it) })
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))

        Log.d(TAG, "Final Annotation Associations: ${finalAnnotations.size} annotations applied.")

        return buildXml(finalRenderList, finalAnnotations, targetDpWidth, targetDpHeight, wrapInScroll)
    }

    private fun scaleDetection(
        detection: DetectionResult, sourceWidth: Int, sourceHeight: Int, targetW: Int, targetH: Int
    ): ScaledBox {
        val rect = detection.boundingBox
        val normCx = rect.centerX() / sourceWidth
        val normCy = rect.centerY() / sourceHeight
        val normW = rect.width() / sourceWidth
        val normH = rect.height() / sourceHeight
        val x = max(0, ((normCx - normW / 2.0) * targetW).roundToInt())
        val y = max(0, ((normCy - normH / 2.0) * targetH).roundToInt())
        val w = max(MIN_W_ANY, (normW * targetW).roundToInt())
        val h = max(MIN_H_ANY, (normH * targetH).roundToInt())
        return ScaledBox(detection.label, detection.text, Rect(x, y, x + w, y + h), detection, x + w / 2, y + h / 2)
    }

    private fun viewTagFor(label: String): String = when (label) {
        "text" -> "TextView"
        "button" -> "Button"
        "image_placeholder", "icon" -> "ImageView"
        else -> "View"
    }

    private fun buildXml(
        boxes: List<ScaledBox>, annotations: Map<DetectionResult, String>, targetDpWidth: Int, targetDpHeight: Int, wrapInScroll: Boolean
    ): String {
        val xml = StringBuilder()
        val maxBottom = boxes.maxOfOrNull { it.rect.bottom } ?: 0
        val needScroll = wrapInScroll && maxBottom > targetDpHeight
        val namespaces = """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" xmlns:tools="http://schemas.android.com/tools""""

        xml.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        val rootTag = if (needScroll) "ScrollView" else "LinearLayout"
        xml.appendLine("<$rootTag $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">")
        xml.appendLine("    <LinearLayout android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        xml.appendLine()

        val counters = mutableMapOf<String, Int>()
        boxes.forEach { box ->
            appendSimpleView(xml, box, counters, "        ", annotations)
            xml.appendLine()
        }

        xml.appendLine("    </LinearLayout>")
        if(needScroll) xml.appendLine("</ScrollView>") else xml.appendLine("</LinearLayout>")
        return xml.toString()
    }

    private fun appendSimpleView(
        xml: StringBuilder, box: ScaledBox, counters: MutableMap<String, Int>, indent: String, annotations: Map<DetectionResult, String>
    ) {
        val label = box.label
        val tag = viewTagFor(label)
        val count = counters.getOrPut(label) { 0 }.let { counters[label] = it + 1; it }
        val defaultId = "${label.replace(Regex("[^a-zA-Z0-9_]"), "_")}_$count"

        val annotationString = annotations[box.detection]

        xml.append("$indent<$tag\n")

        if (annotationString != null) {
            annotationString.lines().forEach { line ->
                if (line.isNotBlank()) xml.append("$indent    $line\n")
            }
            if (!annotationString.contains("android:text=") && tag != "ImageView") {
                xml.append("$indent    android:text=\"${box.text}\"\n")
                xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
            }
        } else {
            // Fallback for widgets with no margin annotation
            xml.append("$indent    android:id=\"@+id/$defaultId\"\n")
            xml.append("$indent    android:layout_width=\"wrap_content\"\n")
            xml.append("$indent    android:layout_height=\"wrap_content\"\n")
            if (tag != "ImageView") {
                xml.append("$indent    android:text=\"${box.text}\"\n")
            }
            if (tag == "ImageView") xml.append("$indent    android:contentDescription=\"${box.label}\"\n")
            xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
        }
        xml.append("$indent/>")
    }
}