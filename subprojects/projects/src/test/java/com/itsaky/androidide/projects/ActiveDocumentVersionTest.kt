package com.itsaky.androidide.projects

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.models.Range
import org.junit.After
import org.junit.Test
import java.nio.file.Paths

/** A document's version and content always move forward together. */
class ActiveDocumentVersionTest {
	private val path = Paths.get("/tmp/adfa5231/Main.kt")

	@After
	fun close() {
		FileManager.onDocumentClose(DocumentCloseEvent(path))
	}

	private fun change(
		text: String,
		version: Int,
	) = DocumentChangeEvent(path, text, text, version, ChangeType.NEW_TEXT, 0, Range.NONE)

	@Test
	fun `a backwards version is rejected and leaves the newer content in place`() {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, "v1", 1))
		FileManager.onDocumentContentChange(change("v3", 3))

		FileManager.onDocumentContentChange(change("v2", 2))

		val document = FileManager.getActiveDocument(path)!!
		assertThat(document.version).isEqualTo(3)
		assertThat(document.content).isEqualTo("v3")
	}

	@Test
	fun `a version and its content are never observed apart`() {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, "v1", 1))
		FileManager.onDocumentContentChange(change("v2", 2))

		val document = FileManager.getActiveDocument(path)!!
		assertThat(document.version to document.content).isEqualTo(2 to "v2")
	}
}
