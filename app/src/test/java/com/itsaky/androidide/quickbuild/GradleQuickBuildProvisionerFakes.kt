package com.itsaky.androidide.quickbuild

import android.content.Context
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.BuildCancellationRequestResult
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.BuildVariantInfo
import com.itsaky.androidide.tooling.api.models.ToolingServerMetadata
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.appdevforall.cotg.quickbuild.service.provision.InstallOutcome
import org.appdevforall.cotg.quickbuild.service.provision.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppInstaller
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

// Test doubles shared by the GradleQuickBuildProvisioner suites that drive a whole provision()
// or rebuildProxyApp() rather than a pure helper. They exist because the provisioner's
// interesting failures all sit in the middle of that run - between the Gradle slot check and
// the install - and each needs the run carried to a different depth. Every double records what
// it was asked for, so a test can assert that a refused build reached nothing further rather
// than only that it returned the right text.

/**
 * The device's single Gradle slot.
 *
 * [busyAtRead] answers each successive `isBuildInProgress` read by its 1-based index, because
 * one proxy app build reads the flag twice - once before staging and once immediately before
 * `executeTasks` - and the two answers can legitimately differ. That is the race the late
 * check exists for, and a fake with one constant flag cannot express it.
 */
internal class FakeBuildService(
	private val busyAtRead: (Int) -> Boolean = { false },
	private val userVisible: Boolean = false,
	private val result: TaskExecutionResult = TaskExecutionResult.SUCCESS,
) : BuildService {
	var busyReads = 0
		private set

	/** The task paths of the last `executeTasks`, or null when no build was ever started. */
	var executedTasks: List<String>? = null
		private set

	/** The `-P` arguments of the last `executeTasks`; empty until one runs. */
	var gradleArgs: List<String> = emptyList()
		private set

	override val isBuildInProgress: Boolean
		get() = busyAtRead(++busyReads)

	override val isUserVisibleBuildInProgress: Boolean
		get() = userVisible

	override fun isToolingServerStarted(): Boolean = true

	override fun metadata(): CompletableFuture<ToolingServerMetadata> = CompletableFuture()

	override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> = CompletableFuture()

	override fun executeTasks(tasks: List<String>): CompletableFuture<TaskExecutionResult> =
		throw AssertionError("the provisioner must build through the TaskExecutionMessage overload, which carries its -P args")

	override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
		executedTasks = message.tasks
		gradleArgs = message.buildParams.gradleArgs
		return CompletableFuture.completedFuture(result)
	}

	override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> = CompletableFuture()
}

/**
 * A project model published by a finished sync.
 *
 * @param projectRoot the directory the provisioner resolves the module dir and setup.json
 *   against.
 * @param workspace null models a sync that has not published yet, which is what the tap-during-
 *   sync wait polls on.
 * @param appModules Android application modules, in the order the provisioner would see them.
 * @param androidModules every Android module; defaults to [appModules] so the common case needs
 *   one argument, and a library-only project passes the two separately.
 */
internal class FakeProjectManager(
	private val projectRoot: File,
	override val workspace: Workspace? = mockk(relaxed = true),
	private val appModules: List<AndroidModule> = emptyList(),
	private val androidModules: List<AndroidModule> = appModules,
) : IProjectManager {
	override val gradleBuild: GradleModels.GradleBuild? = null

	override val projectDirPath: String
		get() = projectRoot.path

	override val projectSyncIssues: List<GradleModels.SyncIssue> = emptyList()

	override val androidBuildVariants: Map<String, BuildVariantInfo> = emptyMap()

	override suspend fun setup(gradleBuild: GradleModels.GradleBuild) = Unit

	override fun getAndroidModules(): List<AndroidModule> = androidModules

	override fun getAndroidAppModules(): List<AndroidModule> = appModules

	override fun getAndroidLibraryModules(): List<AndroidModule> = emptyList()

	override fun findModuleForFile(
		file: File,
		checkExistance: Boolean,
	): ModuleProject? = null

	override fun containsSourceFile(file: Path): Boolean = false

	override fun isAndroidResource(file: File): Boolean = false

	override fun destroy() = Unit
}

/**
 * A project model whose sync never publishes a workspace.
 *
 * @param projectRoot the project directory the provisioner reads before the wait.
 */
internal fun unsyncedProjectManager(projectRoot: File): FakeProjectManager = FakeProjectManager(projectRoot, workspace = null)

/**
 * An Android module as the Build Variants sidebar presents it.
 *
 * Mocked rather than built from a real model: [AndroidModule.getSelectedVariant] reads the
 * PROCESS-WIDE [IProjectManager], so a real module would answer from whatever singleton the
 * test JVM happens to hold instead of from this test's own project.
 *
 * @param path the module's Gradle path.
 * @param variantName the selected variant, or null for the mid-sync read the provisioner
 *   falls back to the default variant on.
 */
internal fun androidModule(
	path: String = ":app",
	variantName: String? = "debug",
): AndroidModule =
	mockk<AndroidModule>().also { module ->
		every { module.path } returns path
		every { module.getSelectedVariant() } returns
			variantName?.let {
				AndroidModels.AndroidVariant
					.newBuilder()
					.setName(it)
					.build()
			}
	}

/**
 * PackageManager's answers about the real applicationId's slot.
 *
 * @param installedUid the occupant's uid, or null for an empty slot.
 * @param certSha256 the occupant's signing cert, or null for one that cannot be read - which
 *   is a refusal, not a match.
 */
internal class FakePackages(
	private val installedUid: Int? = null,
	private val certSha256: String? = null,
) : InstalledPackages {
	/** Every `signingCertSha256` lookup, so a test can assert a cert was not even consulted. */
	var certReads = 0
		private set

	override fun uid(packageName: String): Int? = installedUid

	override fun lastUpdateTime(packageName: String): Long? = null

	override fun apkFile(packageName: String): File? = null

	override fun signingCertSha256(packageName: String): String? {
		certReads++
		return certSha256
	}

	override fun appComponentFactory(packageName: String): String? = null
}

/**
 * An installer that answers with [outcome] and records every call, so "the install was
 * refused" can be asserted as "nothing was ever installed" rather than only as a message.
 *
 * @param outcome what `ensureInstalled` reports.
 */
internal fun recordingInstaller(outcome: InstallOutcome = InstallOutcome.Installed(INSTALLED_UID)): ProxyAppInstaller =
	mockk<ProxyAppInstaller>().also { installer ->
		coEvery { installer.ensureInstalled(any(), any()) } returns outcome
	}

/** An arbitrary uid a successful fake install reports; distinctive so a stray zero shows up. */
internal const val INSTALLED_UID = 10_431

/** The applicationId every fixture setup.json declares. */
internal const val REAL_APPLICATION_ID = "com.example.demo"

/**
 * Writes the report the Gradle plugin would have written for a successful proxy app build.
 *
 * @param moduleDir the module whose `build/` dir owns the report.
 * @param variantName the variant the report belongs to; reports are variant-scoped.
 * @param entryActivity the launcher activity, or null for the No-Activity template, which is a
 *   successful build with nothing to launch.
 */
internal fun writeSetupJson(
	moduleDir: File,
	variantName: String = "debug",
	entryActivity: String? = "com.example.demo.MainActivity",
) {
	val report = File(moduleDir, QuickBuildTaskPaths.setupJson(variantName))
	report.parentFile.mkdirs()
	val apkPath = "app/build/outputs/apk/$variantName/app-$variantName.apk"
	report.writeText(
		buildString {
			append("{\"proxyAppId\":\"$REAL_APPLICATION_ID\",")
			if (entryActivity != null) append("\"entryActivity\":\"$entryActivity\",")
			append("\"apk\":\"$apkPath\"}")
		},
	)
}

/** Writes a setup.json the parser must reject, to reach the unparseable-report arm. */
internal fun writeUnparseableSetupJson(
	moduleDir: File,
	variantName: String = "debug",
) {
	val report = File(moduleDir, QuickBuildTaskPaths.setupJson(variantName))
	report.parentFile.mkdirs()
	// A well-formed JSON object with no proxyAppId: parseable as JSON, unusable as a report,
	// which is the shape a schema drift actually produces.
	report.writeText("{\"apk\":\"app-debug.apk\"}")
}

/**
 * A provisioner wired to fakes, with every seam defaulted to the boring healthy case so each
 * test names only the one thing it is about.
 */
internal fun testProvisioner(
	context: Context,
	projectRoot: File,
	buildService: BuildService? = FakeBuildService(),
	projectManager: IProjectManager = FakeProjectManager(projectRoot, appModules = listOf(androidModule())),
	installer: ProxyAppInstaller = recordingInstaller(),
	packages: InstalledPackages = FakePackages(),
	apkCertSha256: (File) -> String? = { null },
	nextBaselineGeneration: suspend (File) -> Long = { 1L },
	stage: (Context, EnvironmentQuickBuildPaths) -> Unit = { _, _ -> },
): GradleQuickBuildProvisioner =
	GradleQuickBuildProvisioner(
		context = context,
		paths = EnvironmentQuickBuildPaths(context),
		installer = installer,
		packages = packages,
		apkCertSha256 = apkCertSha256,
		nextBaselineGeneration = nextBaselineGeneration,
		stage = stage,
		lookupBuildService = { buildService },
		lookupProjectManager = { projectManager },
	)
