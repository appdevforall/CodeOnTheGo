package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

class DetectionMerger(
    private val enrichedComponents: List<DetectionResult>,
    private val remainingYoloDetections: List<DetectionResult>,
    private val fullImageTextBlocks: List<Text.TextBlock>
) {

    private val containerLabels = setOf("card", "toolbar")

    fun merge(): List<DetectionResult> {
        val finalDetections = mutableListOf<DetectionResult>()
        val usedTextBlocks = mutableSetOf<Text.TextBlock>()

        finalDetections.addAll(enrichedComponents)
        finalDetections.addAll(remainingYoloDetections)

        val containers = remainingYoloDetections.filter { it.label in containerLabels }
        for (container in containers) {
            val candidates = fullImageTextBlocks.filter { it !in usedTextBlocks }
            for (textBlock in candidates) {
                val textBox = textBlock.boundingBox?.let { RectF(it) } ?: continue
                if (container.boundingBox.contains(textBox)) {
                    finalDetections.add(
                        DetectionResult(
                            boundingBox = textBox,
                            label = "text",
                            score = 0.99f,
                            text = textBlock.text.replace("\n", " "),
                            isYolo = false
                        )
                    )
                    usedTextBlocks.add(textBlock)
                }
            }
        }

        val orphanText = fullImageTextBlocks.filter { it !in usedTextBlocks }
        for (textBlock in orphanText) {
            textBlock.boundingBox?.let {
                finalDetections.add(
                    DetectionResult(
                        boundingBox = RectF(it),
                        label = "text",
                        score = 0.99f,
                        text = textBlock.text.replace("\n", " "),
                        isYolo = false
                    )
                )
            }
        }

        return finalDetections
    }
}
