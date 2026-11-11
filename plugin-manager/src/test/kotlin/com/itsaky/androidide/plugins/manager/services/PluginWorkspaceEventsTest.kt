package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.services.FileSaveListener
import com.itsaky.androidide.plugins.services.ProjectLifecycleListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

class PluginWorkspaceEventsTest {
	private lateinit var root: File
	private lateinit var projectA: File
	private lateinit var projectB: File
	private val queued = ArrayDeque<Runnable>()

	@Before
	fun setUp() {
		root =
			File.createTempFile("workspace-events-", "").apply {
				delete()
				mkdirs()
			}
		projectA = File(root, "a").apply { mkdirs() }.canonicalFile
		projectB = File(root, "b").apply { mkdirs() }.canonicalFile
	}

	@After
	fun tearDown() {
		root.deleteRecursively()
	}

	private fun direct() = PluginWorkspaceEvents(Executor { it.run() })

	private fun deferred() = PluginWorkspaceEvents(Executor { queued.addLast(it) })

	private class RecordingListener : ProjectLifecycleListener {
		val calls = mutableListOf<String>()

		override fun onProjectOpened(projectDir: File) {
			calls += "opened ${projectDir.name}"
		}

		override fun onProjectClosed(projectDir: File) {
			calls += "closed ${projectDir.name}"
		}
	}

	@Test
	fun anOpenedProjectIsReportedWithItsCanonicalDirectory() {
		val events = direct()
		val dirs = mutableListOf<File>()
		events.addProjectLifecycleListener(
			"p",
			object : ProjectLifecycleListener {
				override fun onProjectOpened(projectDir: File) {
					dirs += projectDir
				}

				override fun onProjectClosed(projectDir: File) = Unit
			},
		)

		events.projectOpened(File(projectA, "../a"))

		assertEquals(listOf(projectA), dirs)
	}

	@Test
	fun aResyncOfTheOpenProjectIsNotReportedAsAnotherOpen() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectOpened(projectA)

		assertEquals(listOf("opened a"), listener.calls)
	}

	@Test
	fun openingAnotherProjectClosesThePreviousOneFirst() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectOpened(projectB)

		assertEquals(listOf("opened a", "closed a", "opened b"), listener.calls)
	}

	@Test
	fun aCloseIsReportedOnceAndOnlyAfterAnOpen() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectClosed(projectA)
		events.projectOpened(projectA)
		events.projectClosed(projectA)
		events.projectClosed(projectA)

		assertEquals(listOf("opened a", "closed a"), listener.calls)
	}

	@Test
	fun reopeningAClosedProjectIsReportedAgain() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectClosed(projectA)
		events.projectOpened(projectA)

		assertEquals(listOf("opened a", "closed a", "opened a"), listener.calls)
	}

	@Test
	fun aSavedFileIsReportedToSaveListeners() {
		val events = direct()
		val saved = mutableListOf<File>()
		events.addFileSaveListener("p") { saved += it }

		val file = File(projectA, "Main.kt")
		events.fileSaved(file)

		assertEquals(listOf(file), saved)
	}

	@Test
	fun aListenerThatThrowsDoesNotStopTheOthers() {
		val events = direct()
		val heard = mutableListOf<String>()
		// An Error, not only an Exception: a plugin built against another API throws NoSuchMethodError.
		events.addFileSaveListener("broken") { throw NoSuchMethodError("stale plugin") }
		events.addFileSaveListener("crashy") { throw IllegalStateException("bug") }
		events.addFileSaveListener("fine") { heard += it.name }

		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(listOf("Main.kt"), heard)
	}

	@Test
	fun theSameListenerAddedTwiceIsCalledOnce() {
		val events = direct()
		var calls = 0
		val listener = FileSaveListener { calls++ }
		events.addFileSaveListener("p", listener)
		events.addFileSaveListener("p", listener)

		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(1, calls)
	}

	@Test
	fun aRemovedListenerIsNotCalled() {
		val events = direct()
		val listener = RecordingListener()
		var saves = 0
		val saveListener = FileSaveListener { saves++ }
		events.addProjectLifecycleListener("p", listener)
		events.addFileSaveListener("p", saveListener)

		events.removeProjectLifecycleListener("p", listener)
		events.removeFileSaveListener("p", saveListener)
		events.projectOpened(projectA)
		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(emptyList<String>(), listener.calls)
		assertEquals(0, saves)
	}

	@Test
	fun removeAllDropsOnlyThatPluginsListeners() {
		val events = direct()
		val gone = RecordingListener().also { events.addProjectLifecycleListener("gone", it) }
		val kept = RecordingListener().also { events.addProjectLifecycleListener("kept", it) }
		var goneSaves = 0
		events.addFileSaveListener("gone") { goneSaves++ }

		events.removeAll("gone")
		events.projectOpened(projectA)
		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(emptyList<String>(), gone.calls)
		assertEquals(0, goneSaves)
		assertEquals(listOf("opened a"), kept.calls)
	}

	@Test
	fun aPluginRemovedBeforeAQueuedEventRunsIsNotCalled() {
		val events = deferred()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.removeAll("p")
		while (queued.isNotEmpty()) queued.removeFirst().run()

		assertEquals(emptyList<String>(), listener.calls)
	}

	@Test
	fun eventsRunInTheOrderTheyWereReported() {
		val events = deferred()
		val order = mutableListOf<String>()
		events.addProjectLifecycleListener(
			"p",
			object : ProjectLifecycleListener {
				override fun onProjectOpened(projectDir: File) {
					order += "opened"
				}

				override fun onProjectClosed(projectDir: File) {
					order += "closed"
				}
			},
		)
		events.addFileSaveListener("p") { order += "saved" }

		events.projectOpened(projectA)
		events.fileSaved(File(projectA, "Main.kt"))
		events.projectClosed(projectA)
		while (queued.isNotEmpty()) queued.removeFirst().run()

		assertEquals(listOf("opened", "saved", "closed"), order)
	}

	@Test
	fun aListenerAddedWhileAProjectIsOpenHearsThatOpen() {
		val events = direct()
		events.projectOpened(projectA)

		val late = RecordingListener().also { events.addProjectLifecycleListener("late", it) }
		events.projectClosed(projectA)

		assertEquals(listOf("opened a", "closed a"), late.calls)
	}

	@Test
	fun aListenerAddedBeforeAQueuedOpenRunsHearsItOnce() {
		val events = deferred()
		// Another plugin is listening, so the open is really queued, not skipped as unheard.
		events.addProjectLifecycleListener("early", RecordingListener())
		events.projectOpened(projectA)
		val late = RecordingListener().also { events.addProjectLifecycleListener("late", it) }
		while (queued.isNotEmpty()) queued.removeFirst().run()

		assertEquals(listOf("opened a"), late.calls)
	}

	@Test
	fun closingAProjectThatIsNotTheOpenOneIsIgnored() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectClosed(projectB)

		assertEquals(listOf("opened a"), listener.calls)
	}

	@Test
	fun aPluginRemovedWhileAnEventIsBeingDeliveredIsNotCalled() {
		val events = direct()
		var removedHeard = 0
		// The first listener unloads the other plugin while this save is being delivered.
		events.addFileSaveListener("first") { events.removeAll("second") }
		events.addFileSaveListener("second") { removedHeard++ }

		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(0, removedHeard)
	}

	@Test
	fun aSavedFileHasTheSameFormAsTheProjectDirectory() {
		val events = direct()
		val link = File(root, "link").toPath()
		Files.createSymbolicLink(link, projectA.toPath())
		val opened = mutableListOf<File>()
		val saved = mutableListOf<File>()
		events.addProjectLifecycleListener(
			"p",
			object : ProjectLifecycleListener {
				override fun onProjectOpened(projectDir: File) {
					opened += projectDir
				}

				override fun onProjectClosed(projectDir: File) = Unit
			},
		)
		events.addFileSaveListener("p") { saved += it }

		events.projectOpened(link.toFile())
		events.fileSaved(File(link.toFile(), "Main.kt"))

		assertEquals(true, saved.single().startsWith(opened.single()))
	}

	@Test
	fun aPluginCannotRemoveAnotherPluginsListener() {
		val events = direct()
		var heard = 0
		val listener = FileSaveListener { heard++ }
		events.addFileSaveListener("owner", listener)

		events.removeFileSaveListener("other", listener)
		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(1, heard)
	}

	@Test
	fun twoPluginsSharingOneListenerKeepSeparateRegistrations() {
		val events = direct()
		var heard = 0
		val shared = FileSaveListener { heard++ }
		events.addFileSaveListener("a", shared)
		events.addFileSaveListener("b", shared)

		events.removeAll("a")
		events.fileSaved(File(projectA, "Main.kt"))

		assertEquals(1, heard)
	}

	@Test
	fun nothingIsQueuedWhenNoPluginListens() {
		var queuedTasks = 0
		val events = PluginWorkspaceEvents(Executor { queuedTasks++ })

		events.fileSaved(File(projectA, "Main.kt"))
		events.projectOpened(projectA)
		events.projectClosed(projectA)

		assertEquals(0, queuedTasks)
	}

	@Test(expected = SecurityException::class)
	fun addingAProjectListenerWithoutReadPermissionThrows() {
		projectService(permissions = emptySet()).addProjectLifecycleListener(RecordingListener())
	}

	@Test
	fun aProjectListenerAddedThroughTheServiceHearsOpens() {
		val events = direct()
		val listener = RecordingListener()
		projectService(setOf(PluginPermission.FILESYSTEM_READ), events).addProjectLifecycleListener(listener)

		events.projectOpened(projectA)

		assertEquals(listOf("opened a"), listener.calls)
	}

	@Test(expected = SecurityException::class)
	fun addingASaveListenerWithoutReadPermissionThrows() {
		editorService(permissions = emptySet()).addFileSaveListener { }
	}

	@Test
	fun aSaveListenerAddedThroughTheServiceHearsSaves() {
		val events = direct()
		val saved = mutableListOf<File>()
		editorService(setOf(PluginPermission.FILESYSTEM_READ), events).addFileSaveListener { saved += it }

		val file = File(projectA, "Main.kt")
		events.fileSaved(file)

		assertEquals(listOf(file), saved)
	}

	private fun projectService(
		permissions: Set<PluginPermission>,
		events: PluginWorkspaceEvents = direct(),
	) = IdeProjectServiceImpl(
		pluginId = "p",
		permissions = permissions,
		projectProvider = NoProjects,
		workspaceEvents = events,
	)

	private fun editorService(
		permissions: Set<PluginPermission>,
		events: PluginWorkspaceEvents = direct(),
	) = IdeEditorServiceImpl(
		pluginId = "p",
		permissions = permissions,
		editorProvider = NoEditors,
		workspaceEvents = events,
	)

	private object NoProjects : IdeProjectServiceImpl.ProjectProvider {
		override fun getCurrentProject(): IProject? = null

		override fun getAllProjects(): List<IProject> = emptyList()

		override fun getProjectByPath(path: File): IProject? = null
	}

	private object NoEditors : IdeEditorServiceImpl.EditorProvider {
		override fun getCurrentFile(): File? = null

		override fun getOpenFiles(): List<File> = emptyList()

		override fun isFileOpen(file: File): Boolean = false

		override fun getCurrentSelection(): String? = null
	}
}
