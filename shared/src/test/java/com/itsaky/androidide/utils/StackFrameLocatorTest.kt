package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StackFrameLocatorTest {
	@Test
	fun `parses a Java frame out of a rendered logcat line`() {
		val line =
			"09-12 10:41:03.123 4242 4242 E  AndroidRuntime            " +
				"\tat com.example.app.MainActivity.onCreate(MainActivity.java:42)"
		assertThat(StackFrameLocator.parse(line))
			.isEqualTo(StackFrame("com.example.app.MainActivity", "MainActivity.java", 42))
	}

	@Test
	fun `parses a Kotlin lambda frame keeping the synthetic class name`() {
		val line = "\tat com.example.app.MainActivity\$onCreate\$1.invoke(MainActivity.kt:50)"
		assertThat(StackFrameLocator.parse(line))
			.isEqualTo(StackFrame("com.example.app.MainActivity\$onCreate\$1", "MainActivity.kt", 50))
	}

	@Test
	fun `finds the file and line span inside a frame`() {
		val line = "\tat com.example.app.MainActivity.onCreate(MainActivity.java:42)"
		val range = StackFrameLocator.sourceLocationRange(line)
		assertThat(range?.let(line::substring)).isEqualTo("MainActivity.java:42")
		assertThat(StackFrameLocator.sourceLocationRange("Caused by: java.lang.IllegalStateException")).isNull()
	}

	@Test
	fun `ignores lines that are not source frames`() {
		assertThat(StackFrameLocator.parse("E AndroidRuntime: Caused by: java.lang.NullPointerException")).isNull()
		assertThat(StackFrameLocator.parse("\tat java.lang.reflect.Method.invoke(Native Method)")).isNull()
		assertThat(StackFrameLocator.parse("\tat kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Unknown Source:12)"))
			.isNull()
	}

	@Test
	fun `locates a Java source under its package directory`() {
		val root = tempDir()
		val expected = sourceFile(root, "com/example/app/MainActivity.java")
		val frame = StackFrame("com.example.app.MainActivity", "MainActivity.java", 42)
		assertThat(StackFrameLocator.locate(frame, listOf(root))).isEqualTo(expected)
	}

	@Test
	fun `locates a Kotlin file facade by file name in a later source dir`() {
		val javaDir = tempDir()
		val kotlinDir = tempDir()
		val expected = sourceFile(kotlinDir, "com/example/app/Utils.kt")
		val frame = StackFrame("com.example.app.UtilsKt", "Utils.kt", 10)
		assertThat(StackFrameLocator.locate(frame, listOf(javaDir, kotlinDir))).isEqualTo(expected)
	}

	@Test
	fun `locates a default package class at the source root`() {
		val root = tempDir()
		val expected = sourceFile(root, "Main.java")
		assertThat(StackFrameLocator.locate(StackFrame("Main", "Main.java", 3), listOf(root))).isEqualTo(expected)
	}

	@Test
	fun `returns null for a frame whose file is not in any source dir`() {
		val frame = StackFrame("android.app.Activity", "Activity.java", 8000)
		assertThat(StackFrameLocator.locate(frame, listOf(tempDir()))).isNull()
	}

	private fun tempDir(): File = Files.createTempDirectory("src").toFile()

	private fun sourceFile(
		root: File,
		relativePath: String,
	): File =
		File(root, relativePath).also {
			it.parentFile.mkdirs()
			it.createNewFile()
		}
}
