package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.lsp.kotlin.compiler.DEFAULT_JVM_TARGET
import com.itsaky.androidide.lsp.kotlin.compiler.read
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems.JAR_PROTOCOL
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.library.KLIB_FILE_EXTENSION
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private val logger = LoggerFactory.getLogger("KtLibraryModule")

@OptIn(KaPlatformInterface::class)
internal class KtLibraryModule(
	project: Project,
	override val id: String,
	override val contentRoots: Set<Path>,
	dependencies: List<KtModule>,
	private val applicationEnvironment: CoreApplicationEnvironment,
	override val isSdk: Boolean = false,
	private val jvmTarget: JvmTarget = DEFAULT_JVM_TARGET,
	override val librarySources: KaLibrarySourceModule? = null,
) : KaLibraryModule,
	AbstractKtModule(
		project,
		dependencies
	) {

	class Builder(
		private val project: Project,
		private val applicationEnvironment: CoreApplicationEnvironment,
	) {
		lateinit var id: String
		private val contentRoots = mutableSetOf<Path>()
		private val dependencies = mutableListOf<KtModule>()
		var isSdk: Boolean = false
		var jvmTarget: JvmTarget = DEFAULT_JVM_TARGET
		var librarySources: KaLibrarySourceModule? = null

		fun addContentRoot(root: Path) {
			contentRoots.add(root)
		}

		fun addDependency(dep: KtModule) {
			dependencies.add(dep)
		}

		fun build(): KtLibraryModule = KtLibraryModule(
			project = project,
			id = id,
			contentRoots = contentRoots.toSet(),
			dependencies = dependencies.toList(),
			applicationEnvironment = applicationEnvironment,
			isSdk = isSdk,
			jvmTarget = jvmTarget,
			librarySources = librarySources,
		)
	}

	@OptIn(KaImplementationDetail::class)
	override fun computeFiles(extended: Boolean): Sequence<VirtualFile> {
		val roots = if (isSdk) project.read {
			LibraryUtils.findClassesFromJdkHome(
				contentRoots.first(),
				isJre = false
			)
		}
		else contentRoots

		val notExtendedFiles = roots
			.asSequence()
			.mapNotNull { getVirtualFileForLibraryRoot(it, applicationEnvironment, project) }

		if (!extended) return notExtendedFiles

		return notExtendedFiles
			.flatMap { LibraryUtils.getAllVirtualFilesFromRoot(it, includeRoot = true) }
	}

	@OptIn(KaExperimentalApi::class)
	override val baseContentScope: GlobalSearchScope by lazy {
		val virtualFileUrls = computeFiles(extended = true).map { it.url }.toSet()
		object : GlobalSearchScope(project) {
			override fun contains(vf: VirtualFile): Boolean {
				return vf.url in virtualFileUrls
			}

			override fun isSearchInModuleContent(module: Module): Boolean {
				return false
			}

			override fun isSearchInLibraries(): Boolean {
				return true
			}
		}
	}

	override val libraryName: String
		get() = id

	override val binaryRoots: Collection<Path>
		get() = contentRoots

	@KaExperimentalApi
	override val binaryVirtualFiles: Collection<VirtualFile>
		get() = emptyList()

	override val targetPlatform: TargetPlatform
		get() = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)
}

internal fun buildKtLibraryModule(
	project: Project,
	applicationEnvironment: CoreApplicationEnvironment,
	init: KtLibraryModule.Builder.() -> Unit,
): KtLibraryModule = KtLibraryModule.Builder(project, applicationEnvironment).apply(init).build()

private const val JAR_SEPARATOR = "!/"
private fun getVirtualFileForLibraryRoot(
	root: Path,
	environment: CoreApplicationEnvironment,
	project: Project,
): VirtualFile? {
	val pathString = root.absolutePathString()

	// .jar or .klib files
	if (pathString.endsWith(JAR_PROTOCOL) || pathString.endsWith(KLIB_FILE_EXTENSION)) {
		return project.read { environment.jarFileSystem.findFileByPath(pathString + JAR_SEPARATOR) }
	}

	// JDK classes
	if (pathString.contains(JAR_SEPARATOR)) {
		val (libHomePath, pathInImage) = CoreJrtFileSystem.splitPath(pathString)
		val adjustedPath = libHomePath + JAR_SEPARATOR + "modules/$pathInImage"
		return project.read { environment.jrtFileSystem?.findFileByPath(adjustedPath) }
	}

	// Regular .class files
	return project.read { VirtualFileManager.getInstance().findFileByNioPath(root) }
}
