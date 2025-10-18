package com.itsaky.androidide.agent.diff

import com.itsaky.androidide.agent.protocol.FileChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Unit tests for AgentDiffTracker to ensure proper diff generation
 * and prevent issues like negative limit in split().
 */
class AgentDiffTrackerTest {

    private lateinit var tempDir: Path
    private lateinit var diffTracker: AgentDiffTracker

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("agent-diff-test")
        diffTracker = AgentDiffTracker(tempDir)
    }

    @Test
    fun `test empty file changes produces empty diff`() {
        val changes = diffTracker.generateChanges()
        assertTrue("Empty diff tracker should produce empty changes", changes.isEmpty())
    }

    @Test
    fun `test file creation produces valid diff`() {
        val testFile = tempDir.resolve("test.txt")
        val content = "Hello World\nThis is a test\n"

        // Snapshot file before it exists (null content)
        diffTracker.snapshotFile(testFile)

        // Create the file
        testFile.writeText(content)

        // Generate changes
        val changes = diffTracker.generateChanges()

        assertFalse("File creation should produce changes", changes.isEmpty())
        val change = changes[testFile]
        assertTrue("Change should be Add type", change is FileChange.Add)
    }

    @Test
    fun `test file modification produces valid diff`() {
        val testFile = tempDir.resolve("modify.txt")
        val oldContent = "Line 1\nLine 2\nLine 3\n"
        val newContent = "Line 1\nLine 2 Modified\nLine 3\n"

        // Create file
        testFile.writeText(oldContent)

        // Snapshot the original state
        diffTracker.snapshotFile(testFile)

        // Modify file
        testFile.writeText(newContent)

        // Generate changes
        val changes = diffTracker.generateChanges()

        assertFalse("File modification should produce changes", changes.isEmpty())
        val change = changes[testFile]
        assertTrue("Change should be Update type", change is FileChange.Update)
    }

    @Test
    fun `test multiline content does not throw exception`() {
        val testFile = tempDir.resolve("multiline.xml")
        val content = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/textView"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello" />

            </LinearLayout>
        """.trimIndent()

        // Snapshot before creating
        diffTracker.snapshotFile(testFile)

        // This should not throw IllegalArgumentException
        try {
            testFile.writeText(content)
            val changes = diffTracker.generateChanges()

            // Should succeed without exception
            assertFalse("Multiline content should produce changes", changes.isEmpty())
        } catch (e: IllegalArgumentException) {
            throw AssertionError(
                "Should not throw IllegalArgumentException for multiline content",
                e
            )
        }
    }

    @Test
    fun `test content with various line endings`() {
        // Test Unix line endings
        val unixFile = tempDir.resolve("unix.txt")
        val unixContent = "Line 1\nLine 2\nLine 3\n"
        diffTracker.snapshotFile(unixFile)
        unixFile.writeText(unixContent)
        val changes1 = diffTracker.generateChanges()
        assertFalse("Unix line endings should work", changes1.isEmpty())
        diffTracker.clear()

        // Test Windows line endings
        val windowsFile = tempDir.resolve("windows.txt")
        val windowsContent = "Line 1\r\nLine 2\r\nLine 3\r\n"
        diffTracker.snapshotFile(windowsFile)
        windowsFile.writeText(windowsContent)
        val changes2 = diffTracker.generateChanges()
        assertFalse("Windows line endings should work", changes2.isEmpty())
        diffTracker.clear()

        // Test mixed line endings
        val mixedFile = tempDir.resolve("mixed.txt")
        val mixedContent = "Line 1\nLine 2\r\nLine 3\n"
        diffTracker.snapshotFile(mixedFile)
        mixedFile.writeText(mixedContent)
        val changes3 = diffTracker.generateChanges()
        assertFalse("Mixed line endings should work", changes3.isEmpty())
    }

    @Test
    fun `test empty line at end of file is handled correctly`() {
        val testFile = tempDir.resolve("trailing.txt")
        val content = "Line 1\nLine 2\n\n"  // Trailing empty line

        diffTracker.snapshotFile(testFile)
        testFile.writeText(content)
        val changes = diffTracker.generateChanges()

        // Should not crash and should produce valid diff
        assertFalse("Content with trailing newline should produce changes", changes.isEmpty())
    }

    @Test
    fun `test single line without newline`() {
        val testFile = tempDir.resolve("single.txt")
        val content = "Single line no newline"

        diffTracker.snapshotFile(testFile)
        testFile.writeText(content)
        val changes = diffTracker.generateChanges()

        assertFalse("Single line should produce changes", changes.isEmpty())
    }

    @Test
    fun `test empty file creation`() {
        val testFile = tempDir.resolve("empty.txt")
        val content = ""

        diffTracker.snapshotFile(testFile)
        testFile.writeText(content)
        val changes = diffTracker.generateChanges()

        // Empty file creation should not crash
        assertTrue("Empty file creation should not crash", true)
    }

    @Test
    fun `test large file with many lines`() {
        val testFile = tempDir.resolve("large.txt")
        val lines = (1..1000).map { "Line $it" }
        val content = lines.joinToString("\n") + "\n"

        // Should handle large files without issues
        try {
            diffTracker.snapshotFile(testFile)
            testFile.writeText(content)
            val changes = diffTracker.generateChanges()
            assertFalse("Large file should produce changes", changes.isEmpty())
        } catch (e: IllegalArgumentException) {
            throw AssertionError("Should not throw IllegalArgumentException for large files", e)
        }
    }

    @Test
    fun `test file deletion tracking`() {
        val testFile = tempDir.resolve("delete.txt")
        val content = "To be deleted\n"

        // Create file
        testFile.writeText(content)

        // Snapshot the existing file
        diffTracker.snapshotFile(testFile)

        // Delete file
        Files.delete(testFile)

        val changes = diffTracker.generateChanges()

        assertFalse("File deletion should produce changes", changes.isEmpty())
        val change = changes[testFile]
        assertTrue("Change should be Delete type", change is FileChange.Delete)
    }

    @Test
    fun `test multiple file changes in single session`() {
        val file1 = tempDir.resolve("file1.txt")
        val file2 = tempDir.resolve("file2.txt")

        diffTracker.snapshotFile(file1)
        diffTracker.snapshotFile(file2)

        file1.writeText("Content 1\n")
        file2.writeText("Content 2\n")

        val changes = diffTracker.generateChanges()

        assertFalse("Multiple file changes should produce changes", changes.isEmpty())
        assertTrue("Should have file1 change", changes.containsKey(file1))
        assertTrue("Should have file2 change", changes.containsKey(file2))
    }

    @Test
    fun `test clear removes all tracked changes`() {
        val testFile = tempDir.resolve("clear.txt")
        diffTracker.snapshotFile(testFile)
        testFile.writeText("Some content\n")

        // Should have changes
        val changes1 = diffTracker.generateChanges()
        assertFalse("Should have changes before clear", changes1.isEmpty())

        // Clear
        diffTracker.clear()

        // Should be empty after clear
        val changes2 = diffTracker.generateChanges()
        assertTrue("Should have no changes after clear", changes2.isEmpty())
    }

    @Test
    fun `test unicode content handling`() {
        val testFile = tempDir.resolve("unicode.txt")
        val content = "Hello 世界\n你好 World\nこんにちは\n"

        diffTracker.snapshotFile(testFile)
        testFile.writeText(content)
        val changes = diffTracker.generateChanges()

        assertFalse("Unicode content should produce changes", changes.isEmpty())
    }

    @Test
    fun `regression test for negative limit in split`() {
        // This is the specific test case that would have caught the bug
        val testFile = tempDir.resolve("activity_main.xml")
        val content = """
            <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                xmlns:tools="http://schemas.android.com/tools"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                tools:context=".MainActivity">

                <EditText
                    android:id="@+id/numberInput"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:ems="10"
                    android:hint="Enter a number"
                    android:inputType="number"
                    app:layout_constraintBottom_toTopOf="@+id/calculateButton"
                    app:layout_constraintEnd_toEndOf="parent"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toTopOf="parent" />

                <Button
                    android:id="@+id/calculateButton"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Calculate"
                    app:layout_constraintBottom_toBottomOf="parent"
                    app:layout_constraintEnd_toEndOf="parent"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toTopOf="parent" />

                <TextView
                    android:id="@+id/resultView"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Result"
                    android:textSize="24sp"
                    app:layout_constraintBottom_toBottomOf="parent"
                    app:layout_constraintEnd_toEndOf="parent"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toBottomOf="@+id/calculateButton" />

            </androidx.constraintlayout.widget.ConstraintLayout>
        """.trimIndent()

        // This should NOT throw IllegalArgumentException: Limit must be non-negative
        try {
            diffTracker.snapshotFile(testFile)
            testFile.writeText(content)
            val changes = diffTracker.generateChanges()

            assertFalse("XML layout should produce changes", changes.isEmpty())
            assertTrue("Should have change for activity_main.xml", changes.containsKey(testFile))
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Limit must be non-negative") == true) {
                throw AssertionError(
                    "Regression: split() called with negative limit. " +
                            "This was the original bug that should be fixed.",
                    e
                )
            }
            throw e
        }
    }

    @Test
    fun `test content with only newlines`() {
        val testFile = tempDir.resolve("newlines.txt")
        val content = "\n\n\n\n"

        diffTracker.snapshotFile(testFile)
        testFile.writeText(content)
        val changes = diffTracker.generateChanges()

        // Should handle content that is only newlines
        assertTrue("Content with only newlines should work", true)
    }

    @Test
    fun `test special characters in content`() {
        val testFile = tempDir.resolve("special.txt")
        val content = "Tab:\t\nQuote:\"\nBackslash:\\\nNewline test\n"

        diffTracker.snapshotFile(testFile)
        testFile.writeText(content)
        val changes = diffTracker.generateChanges()

        assertFalse("Special characters should produce changes", changes.isEmpty())
    }
}
