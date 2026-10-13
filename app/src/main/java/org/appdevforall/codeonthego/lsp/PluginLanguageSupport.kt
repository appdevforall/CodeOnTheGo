package org.appdevforall.codeonthego.lsp

import android.os.Handler
import android.os.Looper
import org.appdevforall.codeonthego.app.IDEApplication
import org.appdevforall.codeonthego.editor.language.treesitter.PluginGrammars
import org.appdevforall.codeonthego.editor.language.treesitter.PluginTreeSitterLanguage
import org.appdevforall.codeonthego.editor.language.treesitter.TSLanguageRegistry
import org.appdevforall.codeonthego.editor.language.treesitter.TreeSitterLanguage
import org.appdevforall.codeonthego.events.PluginLanguagesChangedEvent
import org.appdevforall.codeonthego.lsp.api.ILanguageServerRegistry
import org.appdevforall.codeonthego.lsp.external.ExternalLanguageServer
import org.appdevforall.codeonthego.plugins.extensions.LanguageServerDefinition
import org.appdevforall.codeonthego.plugins.manager.language.PluginLanguageContribution
import org.appdevforall.codeonthego.preferences.internal.EditorPreferences
import org.appdevforall.codeonthego.utils.Environment
import org.appdevforall.codeonthego.utils.TermuxProcessEnvironment
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object PluginLanguageSupport {
	private val log = LoggerFactory.getLogger(PluginLanguageSupport::class.java)
	private val hostFileTypes = setOf("java", "kt", "kts", "xml", "json", "log", "gradle", "c", "h", "cc", "cpp", "cxx")
	private val mainExecutor = Handler(Looper.getMainLooper()).let { handler -> Executor { handler.post(it) } }
	private val refreshExecutor = Executors.newSingleThreadExecutor { Thread(it, "plugin-languages") }
	private val lock = Any()
	private var installed: List<InstalledLanguage> = emptyList()
	private val changedFileTypes = mutableSetOf<String>()

	fun takeChangedFileTypes(): Set<String> =
		synchronized(lock) {
			changedFileTypes.toSet().also { changedFileTypes.clear() }
		}

	fun serverIdFor(file: File): String? {
		val type = file.extension.lowercase()
		return synchronized(lock) { installed.firstOrNull { type in it.fileTypes }?.serverId }
	}

	fun refresh() {
		refreshExecutor.execute(::reconcile)
	}

	fun registerServers() {
		synchronized(lock) { installed.forEach(::registerServer) }
	}

	fun registerGrammars() {
		synchronized(lock) { installed.forEach(::registerGrammar) }
	}

	private fun registerGrammar(language: InstalledLanguage) {
		language.grammarFactories.forEach { (type, factory) -> TSLanguageRegistry.instance.registerIfNeeded(type, factory) }
	}

	private fun reconcile() {
		val contributions = IDEApplication.getPluginManager()?.getEnabledLanguageContributions() ?: emptyList()
		val changed =
			synchronized(lock) {
				val keep = installed.filter { it.contribution in contributions }
				val removed = installed.filterNot { it in keep }
				removed.forEach(::uninstall)

				val claimed = keep.flatMapTo(mutableSetOf()) { it.fileTypes }
				val added =
					contributions
						.filter { contribution -> keep.none { it.contribution == contribution } }
						.mapNotNull { install(it, claimed) }
				installed = keep + added
				installed.forEach(::registerGrammar)
				installed.forEach(::registerServer)
				changedFileTypes.addAll((removed + added).flatMap { it.fileTypes })
			}
		if (changed) {
			EventBus.getDefault().post(PluginLanguagesChangedEvent())
		}
	}

	private fun install(
		contribution: PluginLanguageContribution,
		claimed: MutableSet<String>,
	): InstalledLanguage? {
		val definition = contribution.definition
		val fileTypes =
			definition.fileExtensions
				.map { it.lowercase() }
				.filter { type ->
					val free = type !in hostFileTypes && type !in claimed
					if (!free) {
						log.warn("Plugin {} cannot claim '.{}' for {}: already handled", contribution.pluginId, type, definition.languageId)
					}
					free
				}.toSet()
		if (fileTypes.isEmpty()) return null
		claimed += fileTypes

		val serverId =
			definition.server?.let {
				"plugin.${contribution.pluginId}.${definition.languageId}.${fileTypes.sorted().joinToString("+")}"
			}
		val factories = grammarFactories(contribution, fileTypes, serverId)
		log.info("Installed {} from plugin {} for {}", definition.languageId, contribution.pluginId, fileTypes)
		return InstalledLanguage(contribution, fileTypes, serverId, factories)
	}

	private fun grammarFactories(
		contribution: PluginLanguageContribution,
		fileTypes: Set<String>,
		serverId: String?,
	): Map<String, TreeSitterLanguage.Factory<*>> {
		val grammar = contribution.definition.grammar ?: return emptyMap()
		val library = File(contribution.nativeLibraryDir, "libtree-sitter-${grammar.name}.so")
		if (!library.isFile) {
			log.error("Plugin {} grammar library not found: {}", contribution.pluginId, library)
			return emptyMap()
		}
		return fileTypes
			.associateWith { type ->
				PluginTreeSitterLanguage.Factory(type, library, grammar.name, contribution.assets, grammar.queriesAssetPath, serverId)
			}
	}

	private fun uninstall(language: InstalledLanguage) {
		language.grammarFactories.forEach { (type, factory) -> TSLanguageRegistry.instance.unregister(type, factory) }
		if (language.grammarFactories.isNotEmpty()) {
			language.contribution.definition.grammar
				?.let { PluginGrammars.retire(it.name) }
		}
		val serverId = language.serverId ?: return
		val registry = ILanguageServerRegistry.default
		if (registry.getServer(serverId) != null) {
			registry.unregister(serverId)
		}
		log.info("Uninstalled {} from plugin {}", language.contribution.definition.languageId, language.contribution.pluginId)
	}

	private fun registerServer(language: InstalledLanguage) {
		val serverId = language.serverId ?: return
		val server = language.contribution.definition.server ?: return
		val registry = ILanguageServerRegistry.default
		if (registry.getServer(serverId) != null) return
		registry.register(
			ExternalLanguageServer(
				serverId = serverId,
				languageId = language.contribution.definition.languageId,
				fileExtensions = language.fileTypes,
				initializationOptions = server.initializationOptions,
				processFactory = { workingDirectory -> startProcess(server, workingDirectory) },
				indentation = { ExternalLanguageServer.Indentation(EditorPreferences.tabSize, EditorPreferences.useSoftTab) },
				uiExecutor = mainExecutor,
			),
		)
	}

	private fun startProcess(
		server: LanguageServerDefinition,
		workingDirectory: File?,
	): Process {
		val command = server.command.toMutableList()
		val executable = command.first()
		if ('/' !in executable) {
			val termuxExecutable = File(Environment.BIN_DIR, executable)
			if (termuxExecutable.canExecute()) {
				command[0] = termuxExecutable.absolutePath
			}
		}
		return ProcessBuilder(command)
			.apply {
				workingDirectory?.let(::directory)
				environment().putAll(server.environment)
				TermuxProcessEnvironment.applyTo(environment(), Environment.PREFIX.parentFile)
			}.start()
	}

	private data class InstalledLanguage(
		val contribution: PluginLanguageContribution,
		val fileTypes: Set<String>,
		val serverId: String?,
		val grammarFactories: Map<String, TreeSitterLanguage.Factory<*>>,
	)
}
