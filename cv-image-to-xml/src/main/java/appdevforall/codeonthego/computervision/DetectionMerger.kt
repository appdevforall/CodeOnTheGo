package appdevforall.codeonthego.computervision

import android.graphics.RectF
import appdevforall.codeonthego.computervision.domain.model.DetectionResult
import com.google.mlkit.vision.text.Text

/**
 * A dedicated, robust class to safely merge YOLO detections with ML Kit text results.
 * This class uses a crash-proof, multi-pass filtering approach that is immune to
 * ConcurrentModificationExceptions.
 */
class DetectionMerger(
    private val yoloResults: List<DetectionResult>,
    private val allTextBlocks: List<Text.TextBlock>
) {

    // Define the roles for different labels for hierarchical processing
    private val componentLabels = setOf("button", "checkbox_checked", "checkbox_unchecked", "switch_on", "switch_off", "chip", "text_entry_box")
    private val containerLabels = setOf("card", "toolbar")

    /**
     * Executes the merging logic by running a series of ordered, non-destructive passes.
     * @return The final, correctly merged list of all UI detections.
     */
    fun merge(): List<DetectionResult> {

        // Pass 1: Interactive components get exclusive first rights to claim text.
        // This returns a map of which text block is now "reserved" by which component.
        val componentTextClaims = claimTextForComponents()

        // Pass 2: Build the final list, respecting the claims made in Pass 1.
        val finalDetections = mutableListOf<DetectionResult>()
        val usedTextBlocks = mutableSetOf<Text.TextBlock>()

        // Add all YOLO results, enriching them with their claimed text.
        for (yoloResult in yoloResults) {
            val claimedText = componentTextClaims[yoloResult]
            if (claimedText != null) {
                yoloResult.text = claimedText.text.replace("\n", " ")
                usedTextBlocks.add(claimedText)
            }
            finalDetections.add(yoloResult)
        }

        // Pass 3: Process containers and add their *unclaimed* internal content.
        val yoloContainers = yoloResults.filter { it.label in containerLabels }
        for (container in yoloContainers) {
            val contentCandidates = allTextBlocks.filter { it !in usedTextBlocks }

            for (textBlock in contentCandidates) {
                val textBox = textBlock.boundingBox?.let { RectF(it) } ?: continue
                if (container.boundingBox.contains(textBox)) {
                    // This text is inside the container and was not claimed by a button,
                    // so it must be a standalone TextView within the card.
                    finalDetections.add(
                        DetectionResult(
                            boundingBox = textBox,
                            label = "text",
                            score = 0.99f,
                            text = textBlock.text.replace("\n", " "),
                            isYolo = false
                        )
                    )
                    // Mark it as used so it doesn't also become an orphan.
                    usedTextBlocks.add(textBlock)
                }
            }
        }

        // Pass 4: Any text that is still unused after all other passes is a true orphan.
        val orphanText = allTextBlocks.filter { it !in usedTextBlocks }
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

    /**
     * Finds the single best text block for each interactive component based on IoU score.
     * This method is non-destructive and only returns a "plan" of claims.
     * @return A map where the key is the component and the value is the text block it has claimed.
     */
    private fun claimTextForComponents(): Map<DetectionResult, Text.TextBlock> {
        val claims = mutableMapOf<DetectionResult, Text.TextBlock>()
        val yoloComponents = yoloResults.filter { it.label in componentLabels }
        val availableText = allTextBlocks.toMutableSet() // Use a set for efficient removal

        for (component in yoloComponents) {
            val bestMatch = availableText
                .mapNotNull { textBlock ->
                    textBlock.boundingBox?.let { box ->
                        val iou = calculateIoU(component.boundingBox, RectF(box))
                        Triple(textBlock, iou, component)
                    }
                }
                .filter { it.second > 0.05 } // Must have at least some meaningful overlap
                .maxByOrNull { it.second } // Get the best match based on IoU

            // If a best match was found, reserve it and remove it from future consideration.
            bestMatch?.let { (textBlock, _, matchedComponent) ->
                claims[matchedComponent] = textBlock
                availableText.remove(textBlock)
            }
        }
        return claims
    }

    /**
     * Utility function to calculate Intersection over Union (IoU).
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val xA = maxOf(box1.left, box2.left)
        val yA = maxOf(box1.top, box2.top)
        val xB = minOf(box1.right, box2.right)
        val yB = minOf(box1.bottom, box2.bottom)

        val intersectionArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea == 0f) 0f else intersectionArea / unionArea
    }
}