package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
class DiagnosticsFormatterTest {

    private fun makeItem(
        message: String,
        severity: DiagnosticSeverity,
        line: Int = 0,
        col: Int = 0,
        code: String = ""
    ) = DiagnosticItem(
        message = message,
        code = code,
        range = Range(Position(line, col), Position(line, col)),
        source = "test",
        severity = severity
    )

    private fun file(name: String) = File("/project/src/$name")

    // --- empty map ---

    @Test
    fun `empty map returns no diagnostics message`() {
        val result = DiagnosticsFormatter.format(emptyMap())
        assertThat(result).isEqualTo("No diagnostics")
    }

    // --- header and summary always present ---

    @Test
    fun `non-empty map contains header and summary`() {
        val diagnostics = mapOf(
            file("Foo.kt") to listOf(makeItem("Error", DiagnosticSeverity.ERROR))
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("=== Diagnostics Report ===")
        assertThat(result).contains("=== Summary ===")
    }

    // --- error and warning counts in per-file header ---

    @Test
    fun `file header shows correct error and warning counts`() {
        val diagnostics = mapOf(
            file("Bar.kt") to listOf(
                makeItem("E1", DiagnosticSeverity.ERROR),
                makeItem("E2", DiagnosticSeverity.ERROR),
                makeItem("W1", DiagnosticSeverity.WARNING)
            )
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Bar.kt (2 errors, 1 warnings)")
    }

    @Test
    fun `file with only warnings shows zero errors`() {
        val diagnostics = mapOf(
            file("Baz.kt") to listOf(makeItem("W1", DiagnosticSeverity.WARNING))
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Baz.kt (0 errors, 1 warnings)")
    }

    @Test
    fun `file with only errors shows zero warnings`() {
        val diagnostics = mapOf(
            file("Qux.kt") to listOf(makeItem("E1", DiagnosticSeverity.ERROR))
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Qux.kt (1 errors, 0 warnings)")
    }

    // --- severity labels in output ---

    @Test
    fun `ERROR severity label is written`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("boom", DiagnosticSeverity.ERROR))
        )
        assertThat(DiagnosticsFormatter.format(diagnostics)).contains("[ERROR]")
    }

    @Test
    fun `WARNING severity label is written`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("watch out", DiagnosticSeverity.WARNING))
        )
        assertThat(DiagnosticsFormatter.format(diagnostics)).contains("[WARNING]")
    }

    @Test
    fun `INFO severity label is written`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("fyi", DiagnosticSeverity.INFO))
        )
        assertThat(DiagnosticsFormatter.format(diagnostics)).contains("[INFO]")
    }

    @Test
    fun `HINT severity label is written`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("tip", DiagnosticSeverity.HINT))
        )
        assertThat(DiagnosticsFormatter.format(diagnostics)).contains("[HINT]")
    }

    // --- line and column are 1-indexed in output ---

    @Test
    fun `line and column are reported as 1-indexed`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("oops", DiagnosticSeverity.ERROR, line = 4, col = 9))
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Line 5:10")
    }

    @Test
    fun `zero-based line 0 col 0 reports as Line 1 col 1`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("oops", DiagnosticSeverity.ERROR, line = 0, col = 0))
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Line 1:1")
    }

    // --- diagnostic message in output ---

    @Test
    fun `diagnostic message text is present in output`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("Cannot resolve symbol 'Foo'", DiagnosticSeverity.ERROR))
        )
        assertThat(DiagnosticsFormatter.format(diagnostics)).contains("Cannot resolve symbol 'Foo'")
    }

    // --- code field is shown only when non-empty ---

    @Test
    fun `diagnostic code is shown when present`() {
        val item = makeItem("error", DiagnosticSeverity.ERROR, code = "E001")
        val diagnostics = mapOf(file("A.kt") to listOf(item))
        assertThat(DiagnosticsFormatter.format(diagnostics)).contains("Code: E001")
    }

    @Test
    fun `diagnostic code is omitted when blank`() {
        val item = makeItem("error", DiagnosticSeverity.ERROR, code = "")
        val diagnostics = mapOf(file("A.kt") to listOf(item))
        assertThat(DiagnosticsFormatter.format(diagnostics)).doesNotContain("Code:")
    }

    // --- sorting ---

    @Test
    fun `files are sorted alphabetically by name`() {
        val diagnostics = mapOf(
            file("Zebra.kt") to listOf(makeItem("z", DiagnosticSeverity.ERROR)),
            file("Apple.kt") to listOf(makeItem("a", DiagnosticSeverity.ERROR)),
            file("Mango.kt") to listOf(makeItem("m", DiagnosticSeverity.ERROR))
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        val appleIdx = result.indexOf("Apple.kt")
        val mangoIdx = result.indexOf("Mango.kt")
        val zebraIdx = result.indexOf("Zebra.kt")
        assertThat(appleIdx).isLessThan(mangoIdx)
        assertThat(mangoIdx).isLessThan(zebraIdx)
    }

    @Test
    fun `diagnostics within a file are sorted by line number`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(
                makeItem("line10", DiagnosticSeverity.ERROR, line = 9),
                makeItem("line1", DiagnosticSeverity.ERROR, line = 0),
                makeItem("line5", DiagnosticSeverity.ERROR, line = 4)
            )
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        val line1Idx = result.indexOf("line1")
        val line5Idx = result.indexOf("line5")
        val line10Idx = result.indexOf("line10")
        assertThat(line1Idx).isLessThan(line5Idx)
        assertThat(line5Idx).isLessThan(line10Idx)
    }

    // --- summary totals ---

    @Test
    fun `summary counts aggregate across all files`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(
                makeItem("e1", DiagnosticSeverity.ERROR),
                makeItem("w1", DiagnosticSeverity.WARNING)
            ),
            file("B.kt") to listOf(
                makeItem("e2", DiagnosticSeverity.ERROR)
            )
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Total: 2 errors, 1 warnings")
    }

    @Test
    fun `summary files count only files with items`() {
        val diagnostics = mapOf(
            file("A.kt") to listOf(makeItem("e", DiagnosticSeverity.ERROR)),
            file("B.kt") to emptyList<DiagnosticItem>()
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).contains("Files: 1")
    }

    // --- files with empty diagnostic lists are skipped ---

    @Test
    fun `files with empty diagnostic list are not included in body`() {
        val diagnostics = mapOf(
            file("Empty.kt") to emptyList<DiagnosticItem>()
        )
        val result = DiagnosticsFormatter.format(diagnostics)
        assertThat(result).doesNotContain("Empty.kt")
    }
}
