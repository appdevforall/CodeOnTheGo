package com.itsaky.androidide.editor.processing

import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.TextRange

class SimpleMacroProcessor : TextProcessor {

    private val macro = "//abc "

    override fun canProcess(line: String, cursorPosition: Int): Boolean {
        // Check if the macro text appears right before the cursor
        val start = cursorPosition - macro.length
        return start >= 0 && line.substring(start, cursorPosition) == macro
    }

    override suspend fun process(context: ProcessContext): ProcessResult {
        val line = context.cursor.rightLine
        val column = context.cursor.rightColumn

        val startColumn = column - macro.length
        val endColumn = column

        return ProcessResult(
            replacement = """
                        // --- Input Data ---
                        int[] sortedArray = {2, 5, 8, 12, 16, 23, 38, 56, 72, 91};
                        
                        // --- Sample 1: Target exists ---
                        int targetToFind = 23;
                        int resultIndex = binarySearch(sortedArray, targetToFind);
                        System.out.println("Input Array: [2, 5, 8, 12, 16, 23, 38, 56, 72, 91]");
                        System.out.println("Searching for: " + targetToFind);
                        System.out.println("Output (Index): " + resultIndex);
                        
                        System.out.println("------------------------------------");

                        // --- Sample 2: Target does not exist ---
                        int targetToMiss = 15;
                        int missingIndex = binarySearch(sortedArray, targetToMiss);
                        System.out.println("Input Array: [2, 5, 8, 12, 16, 23, 38, 56, 72, 91]");
                        System.out.println("Searching for: " + targetToMiss);
                        System.out.println("Output (Index): " + missingIndex);
                        
                        }
                        
public static int binarySearch(int[] arr, int target) {
    int low = 0;
            int high = arr.length - 1;

        while (low <= high) {
            int mid = low + (high - low) / 2; // Prevents potential overflow

            if (arr[mid] == target) {
                return mid; // Found the target! 🎉
            } else if (arr[mid] < target) {
                low = mid + 1; // Target is in the right half
            } else {
                high = mid - 1; // Target is in the left half
            }
        }
        return -1; // Target was not found
    
            """.trimIndent(),
            range = TextRange(
                CharPosition(line, startColumn), CharPosition(line, endColumn)
            )
        )
    }
}