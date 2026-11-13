package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.kotlin.compiler.vfs.ListingJarFileSystem
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment.Companion.getOrCreateApplicationEnvironmentForProduction as acquireApplicationEnvironment

@OptIn(K1Deprecation::class)
internal object KotlinApplicationEnvironmentPin {
	private var pinned = false

	private val pin = Disposer.newDisposable("kotlin-lsp-application-environment-pin")

	/**
	 * The one JAR file system of the process: code compares files with the environment's
	 * `jarFileSystem` by identity, and each instance listens to the shared listing cache.
	 */
	private val jarFileSystem by lazy { ListingJarFileSystem(JarListingCache.SHARED) }

	/**
	 * Makes every Kotlin application environment created after this call, in production or unit
	 * test mode, use the shared listing-backed JAR file system.
	 */
	fun useListingJarFileSystem() {
		KotlinCoreApplicationEnvironment.jarFileSystemFactory = { jarFileSystem }
	}

	@Synchronized
	fun ensure(configuration: CompilerConfiguration) {
		if (pinned) return
		val environment = acquireApplicationEnvironment(pin, configuration)
		check(environment.jarFileSystem is ListingJarFileSystem) {
			"The Kotlin application environment was created before its JAR file system was set"
		}
		pinned = true
	}
}
