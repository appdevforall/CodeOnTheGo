package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.services.FileSaveListener
import com.itsaky.androidide.plugins.services.ProjectLifecycleListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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

		assertThat(dirs).containsExactly(projectA)
	}

	@Test
	fun aResyncOfTheOpenProjectIsNotReportedAsAnotherOpen() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectOpened(projectA)

		assertThat(listener.calls).containsExactly("opened a")
	}

	@Test
	fun openingAnotherProjectClosesThePreviousOneFirst() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectOpened(projectB)

		assertThat(listener.calls).containsExactly("opened a", "closed a", "opened b").inOrder()
	}

	@Test
	fun aCloseIsReportedOnceAndOnlyAfterAnOpen() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectClosed(projectA)
		events.projectOpened(projectA)
		events.projectClosed(projectA)
		events.projectClosed(projectA)

		assertThat(listener.calls).containsExactly("opened a", "closed a").inOrder()
	}

	@Test
	fun reopeningAClosedProjectIsReportedAgain() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectClosed(projectA)
		events.projectOpened(projectA)

		assertThat(listener.calls).containsExactly("opened a", "closed a", "opened a").inOrder()
	}

	@Test
	fun aSavedFileIsReportedToSaveListeners() {
		val events = direct()
		val saved = mutableListOf<File>()
		events.addFileSaveListener("p") { saved += it }

		val file = File(projectA, "Main.kt")
		events.fileSaved(file)

		assertThat(saved).containsExactly(file)
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

		assertThat(heard).containsExactly("Main.kt")
	}

	@Test
	fun theSameListenerAddedTwiceIsCalledOnce() {
		val events = direct()
		var calls = 0
		val listener = FileSaveListener { calls++ }
		events.addFileSaveListener("p", listener)
		events.addFileSaveListener("p", listener)

		events.fileSaved(File(projectA, "Main.kt"))

		assertThat(calls).isEqualTo(1)
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

		assertThat(listener.calls).isEmpty()
		assertThat(saves).isEqualTo(0)
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

		assertThat(gone.calls).isEmpty()
		assertThat(goneSaves).isEqualTo(0)
		assertThat(kept.calls).containsExactly("opened a")
	}

	@Test
	fun aPluginRemovedBeforeAQueuedEventRunsIsNotCalled() {
		val events = deferred()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.removeAll("p")
		while (queued.isNotEmpty()) queued.removeFirst().run()

		assertThat(listener.calls).isEmpty()
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

		assertThat(order).containsExactly("opened", "saved", "closed").inOrder()
	}

	@Test
	fun aListenerAddedWhileAProjectIsOpenHearsThatOpen() {
		val events = direct()
		events.projectOpened(projectA)

		val late = RecordingListener().also { events.addProjectLifecycleListener("late", it) }
		events.projectClosed(projectA)

		assertThat(late.calls).containsExactly("opened a", "closed a").inOrder()
	}

	@Test
	fun aListenerAddedBeforeAQueuedOpenRunsHearsItOnce() {
		val events = deferred()
		// Another plugin is listening, so the open is really queued, not skipped as unheard.
		events.addProjectLifecycleListener("early", RecordingListener())
		events.projectOpened(projectA)
		val late = RecordingListener().also { events.addProjectLifecycleListener("late", it) }
		while (queued.isNotEmpty()) queued.removeFirst().run()

		assertThat(late.calls).containsExactly("opened a")
	}

	@Test
	fun closingAProjectThatIsNotTheOpenOneIsIgnored() {
		val events = direct()
		val listener = RecordingListener().also { events.addProjectLifecycleListener("p", it) }

		events.projectOpened(projectA)
		events.projectClosed(projectB)

		assertThat(listener.calls).containsExactly("opened a")
	}

	@Test
	fun aPluginRemovedWhileAnEventIsBeingDeliveredIsNotCalled() {
		val events = direct()
		var removedHeard = 0
		// The first listener unloads the other plugin while this save is being delivered.
		events.addFileSaveListener("first") { events.removeAll("second") }
		events.addFileSaveListener("second") { removedHeard++ }

		events.fileSaved(File(projectA, "Main.kt"))

		assertThat(removedHeard).isEqualTo(0)
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

		assertThat(saved.single().startsWith(opened.single())).isTrue()
	}

	@Test
	fun aPluginCannotRemoveAnotherPluginsListener() {
		val events = direct()
		var heard = 0
		val listener = FileSaveListener { heard++ }
		events.addFileSaveListener("owner", listener)

		events.removeFileSaveListener("other", listener)
		events.fileSaved(File(projectA, "Main.kt"))

		assertThat(heard).isEqualTo(1)
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

		assertThat(heard).isEqualTo(1)
	}

	@Test
	fun nothingIsQueuedWhenNoPluginListens() {
		var queuedTasks = 0
		val events = PluginWorkspaceEvents(Executor { queuedTasks++ })

		events.fileSaved(File(projectA, "Main.kt"))
		events.projectOpened(projectA)
		events.projectClosed(projectA)

		assertThat(queuedTasks).isEqualTo(0)
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

		assertThat(listener.calls).containsExactly("opened a")
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

		assertThat(saved).containsExactly(file)
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

	@Test
	fun registrationsChangedOnManyThreadsAreNeitherLostNorDoubled() {
		// One plugin registers while another plugin's listeners are dropped, as at startup when a
		// disabled plugin loads beside one that activates. The registration must survive, once.
		val saved = File(projectA, "Main.kt")
		val pool = Executors.newFixedThreadPool(2)
		val start = CyclicBarrier(2)
		var wrong = 0
		try {
			repeat(20_000) {
				val events = direct()
				repeat(8) { i -> events.addFileSaveListener("other", FileSaveListener { _ -> i.hashCode() }) }
				val heard = AtomicInteger()
				val listener = FileSaveListener { heard.incrementAndGet() }
				val drop =
					pool.submit {
						start.await()
						events.removeAll("other")
					}
				val add =
					pool.submit {
						start.await()
						events.addFileSaveListener("p", listener)
					}
				drop.get()
				add.get()
				events.fileSaved(saved)
				if (heard.get() != 1) wrong++
			}
		} finally {
			pool.shutdownNow()
		}
		assertThat(wrong).isEqualTo(0)
	}
}
