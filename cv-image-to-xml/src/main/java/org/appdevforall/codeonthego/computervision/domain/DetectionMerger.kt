package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.utils.OcrTextAssembler.joinElementsWithTolerance

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

        val orphanDetections = fullImageTextBlocks
            .filter { it !in usedTextBlocks }
            .flatMap { it.lines }
            .mapNotNull { line ->
                line.boundingBox?.let { box ->
                    DetectionResult(
                        boundingBox = RectF(box),
                        label = "text",
                        score = 0.99f,
                        text = joinElementsWithTolerance(line),
                        isYolo = false
                    )
                }
            }

        finalDetections.addAll(orphanDetections)

        return finalDetections
    }
}
