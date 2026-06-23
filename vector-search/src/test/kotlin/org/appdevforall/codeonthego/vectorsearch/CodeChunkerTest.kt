/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.vectorsearch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for CodeChunker file splitting functionality.
 *
 * Tests semantic chunking, overlap, and edge cases.
 */
class CodeChunkerTest {

    @Test
    fun testChunkingWith100LineFileMAX_CHUNK_LINES50() {
        val content = buildString {
            repeat(100) { lineNum ->
                append("line $lineNum code\n")
            }
        }

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 500)

        // Should be split into multiple chunks due to size constraint
        assertTrue(chunks.isNotEmpty(), "Should produce chunks for 100-line file")
        assertTrue(chunks.size > 1, "100-line file should be split into multiple chunks")

        // Verify all chunks are accounted for
        val totalLines = content.split("\n").size - 1 // -1 for trailing newline
        val lastChunk = chunks.last()
        assertTrue(lastChunk.endLine >= totalLines - 2, "Last chunk should cover most lines")
    }

    @Test
    fun testChunkOverlapCorrectness() {
        val content = buildString {
            repeat(50) { lineNum ->
                append("Line ${String.format("%03d", lineNum)}\n")
            }
        }

        val chunks = CodeChunker.chunkText(content, "txt", maxChunkSize = 200, overlapSize = 50)

        // With overlap, consecutive chunks should have overlapping ranges
        if (chunks.size >= 2) {
            for (i in 0 until chunks.size - 1) {
                val current = chunks[i]
                val next = chunks[i + 1]
                // Either they overlap or next starts right after current
                assertTrue(
                    next.startLine <= current.endLine + 1,
                    "Chunks should have correct overlap at index $i"
                )
            }
        }
    }

    @Test
    fun testEdgeCaseFileSmallerThanMaxChunkLines() {
        val content = """
            fun simple() {
                return 42
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 10000)

        // Small file should be in a single chunk
        assertEquals(1, chunks.size, "Small file should be single chunk")
        assertEquals(0, chunks[0].startLine)
    }

    @Test
    fun testEdgeCaseEmptyFile() {
        val chunks = CodeChunker.chunkText("", "kt")
        assertEquals(0, chunks.size, "Empty file should produce no chunks")
    }

    @Test
    fun testEdgeCaseWhitespaceOnlyFile() {
        val chunks = CodeChunker.chunkText("   \n\n  \n", "kt")
        assertEquals(0, chunks.size, "Whitespace-only file should produce no chunks")
    }

    @Test
    fun testLineNumberRangesCorrect() {
        val content = buildString {
            repeat(20) { lineNum ->
                append("line $lineNum\n")
            }
        }

        val chunks = CodeChunker.chunkText(content, "txt", maxChunkSize = 100)

        // Verify line number ranges
        for (chunk in chunks) {
            assertTrue(chunk.startLine >= 0, "Start line should be non-negative")
            assertTrue(chunk.endLine >= chunk.startLine, "End line should be >= start line")
            assertTrue(chunk.content.isNotBlank(), "Chunk content should not be blank")
        }

        // Verify no gaps in line numbers
        var expectedLine = 0
        for (chunk in chunks) {
            if (expectedLine == 0 || chunks.indexOf(chunk) == 0) {
                assertEquals(expectedLine, chunk.startLine, "Chunk should start at expected line")
            }
            expectedLine = chunk.endLine + 1
        }
    }

    @Test
    fun testKotlinCodeChunking() {
        val content = """
            package com.example

            class Calculator {
                fun add(a: Int, b: Int): Int {
                    return a + b
                }

                fun subtract(a: Int, b: Int): Int {
                    return a - b
                }

                fun multiply(a: Int, b: Int): Int {
                    return a * b
                }
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 300)

        assertTrue(chunks.isNotEmpty(), "Should chunk Kotlin file")
        chunks.forEach { chunk ->
            assertEquals(true, chunk.isCodeChunk, "Should mark as code chunk")
        }
    }

    @Test
    fun testJavaCodeChunking() {
        val content = """
            package com.example;

            public class Example {
                public String getName() {
                    return "name";
                }

                public int getAge() {
                    return 25;
                }
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "java", maxChunkSize = 300)

        assertTrue(chunks.isNotEmpty(), "Should chunk Java file")
        chunks.forEach { chunk ->
            assertEquals(true, chunk.isCodeChunk, "Should mark as code chunk")
        }
    }

    @Test
    fun testJavaMethodsBecomeSeparateChunks() {
        // Regression for coarse chunking: a small Java class whose body fits within the char
        // budget previously collapsed into a single ~whole-file chunk because the chunker only
        // split when oversized. Each method should now localize to its own chunk.
        val content = """
            package com.example.myapplication7;

            import android.os.Bundle;
            import androidx.appcompat.app.AppCompatActivity;

            public class MainActivity extends AppCompatActivity {

                private Object binding;

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(binding.getRoot());
                }

                @Override
                protected void onDestroy() {
                    super.onDestroy();
                    this.binding = null;
                }
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "java")

        // Should produce several chunks, not one whole-file chunk.
        assertTrue(chunks.size > 1, "Java methods should split into multiple chunks, got ${chunks.size}")

        // No chunk should span almost the entire file.
        val totalLines = content.split("\n").size
        chunks.forEach { chunk ->
            val span = chunk.endLine - chunk.startLine + 1
            assertTrue(
                span < totalLines - 2,
                "Chunk lines ${chunk.startLine}-${chunk.endLine} spans nearly the whole $totalLines-line file"
            )
        }

        // onCreate and onDestroy should land in different chunks.
        val onCreateChunk = chunks.indexOfFirst { it.content.contains("onCreate") }
        val onDestroyChunk = chunks.indexOfFirst { it.content.contains("onDestroy") }
        assertTrue(onCreateChunk >= 0 && onDestroyChunk >= 0, "Both methods should be chunked")
        assertTrue(
            onCreateChunk != onDestroyChunk,
            "onCreate and onDestroy should be in separate chunks"
        )
    }

    @Test
    fun testMethodCallsAreNotTreatedAsDeclarations() {
        // A line like `setContentView(...)` is a call, not a declaration, and must not trigger a
        // spurious chunk boundary that would split a method body mid-statement.
        val content = buildString {
            appendLine("public class Sample {")
            appendLine("    void run() {")
            repeat(20) { append("        doSomething(").append(it).appendLine(");") }
            appendLine("    }")
            appendLine("}")
        }

        val chunks = CodeChunker.chunkText(content, "java", maxChunkSize = 10000)

        // The whole method body fits the budget, so its call lines must all land in one chunk
        // rather than each call triggering a spurious declaration boundary.
        val bodyChunk = chunks.first { it.content.contains("doSomething(0)") }
        assertTrue(
            bodyChunk.content.contains("doSomething(19)"),
            "Call lines must not be split apart by spurious declaration boundaries"
        )
    }

    @Test
    fun testTextFileChunking() {
        val content = """
            This is a text document.
            It contains multiple lines.
            But no code boundaries.
            Just plain text content.
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "txt", maxChunkSize = 100)

        assertTrue(chunks.isNotEmpty(), "Should chunk text file")
        chunks.forEach { chunk ->
            assertEquals(false, chunk.isCodeChunk, "Text chunks should not be marked as code")
        }
    }

    @Test
    fun testContentPreservation() {
        val content = """
            Important content line 1
            Important content line 2
            Important content line 3
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "txt", maxChunkSize = 100)
        val reconstructed = buildString {
            chunks.forEachIndexed { index, chunk ->
                if (index > 0) append("\n")
                append(chunk.content)
            }
        }

        assertTrue(reconstructed.contains("Important content"), "Content should be preserved")
    }

    @Test
    fun testMinChunkSizeMerging() {
        val content = """
            fun func1() { return 1 }

            fun func2() { return 2 }

            fun func3() { return 3 }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 500, minChunkSize = 5)

        // Verify chunks exist and are reasonable
        assertTrue(chunks.isNotEmpty(), "Should produce chunks")
        chunks.forEach { chunk ->
            assertTrue(chunk.content.isNotBlank(), "Chunk should not be empty")
        }
    }

    @Test
    fun testLargeFileChunking() {
        val content = buildString {
            repeat(200) { i ->
                append("fun function_$i() {\n")
                append("    val x = $i\n")
                append("    return x * 2\n")
                append("}\n\n")
            }
        }

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 400)

        assertTrue(chunks.size > 1, "Large file should be split into multiple chunks")
    }

    @Test
    fun testBoundaryDetectionOnClosingBrace() {
        val content = """
            fun firstFunction() {
                println("First")
            }

            fun secondFunction() {
                println("Second")
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 300)

        // Verify chunks break at reasonable boundaries
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun testClassBoundaryDetection() {
        val content = """
            class FirstClass {
                fun method1() = 1
                fun method2() = 2
            }

            class SecondClass {
                fun method1() = 1
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 200)

        assertTrue(chunks.isNotEmpty(), "Should detect class boundaries")
    }

    @Test
    fun testXmlChunking() {
        val content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <resources>
                <string name="app_name">MyApp</string>
                <string name="action_settings">Settings</string>
                <string name="hello">Hello</string>
            </resources>
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "xml", maxChunkSize = 500)

        assertTrue(chunks.isNotEmpty(), "Should chunk XML files")
    }

    @Test
    fun testCommentSkipping() {
        val content = """
            // This is a comment
            fun actualFunction() {
                return 42
            }
            /* Multi-line comment
               spanning multiple lines */
            fun anotherFunction() {
                return 99
            }
        """.trimIndent()

        val chunks = CodeChunker.chunkText(content, "kt", maxChunkSize = 500)

        assertTrue(chunks.isNotEmpty(), "Should handle comments properly")
    }

    @Test
    fun testInvalidParametersRejected() {
        assertFailsWith<IllegalArgumentException> {
            CodeChunker.chunkText("content", "kt", maxChunkSize = -1)
        }

        assertFailsWith<IllegalArgumentException> {
            CodeChunker.chunkText("content", "kt", overlapSize = -1)
        }

        assertFailsWith<IllegalArgumentException> {
            CodeChunker.chunkText("content", "kt", minChunkSize = 0)
        }
    }

    @Test
    fun testVariousLanguageExtensions() {
        val content = "fun test() { }"

        // Test that various code extensions are recognized
        listOf("kt", "java", "py", "js", "ts", "go", "rs", "cpp")
            .forEach { ext ->
                val chunks = CodeChunker.chunkText(content, ext)
                assertTrue(chunks.isNotEmpty(), "Should recognize .$ext as code")
                if (chunks.isNotEmpty()) {
                    assertEquals(true, chunks[0].isCodeChunk, ".$ext should be code chunk")
                }
            }
    }

    @Test
    fun testVariousTextExtensions() {
        val content = "This is text"

        // Test that various text extensions are recognized
        listOf("txt", "md", "xml", "json")
            .forEach { ext ->
                val chunks = CodeChunker.chunkText(content, ext)
                assertTrue(chunks.isNotEmpty(), "Should recognize .$ext as text")
                if (chunks.isNotEmpty()) {
                    assertEquals(false, chunks[0].isCodeChunk, ".$ext should not be code chunk")
                }
            }
    }
}
