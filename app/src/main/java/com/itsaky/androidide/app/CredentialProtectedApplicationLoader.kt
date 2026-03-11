package com.itsaky.androidide.app

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.blankj.utilcode.util.ThrowableUtils
import com.google.android.material.color.DynamicColors
import com.itsaky.androidide.activities.CrashHandlerActivity
import com.itsaky.androidide.activities.editor.IDELogcatReader
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.javac.config.JavacConfigProvider
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.resources.localization.LocaleProvider
import com.itsaky.androidide.ui.themes.IDETheme
import com.itsaky.androidide.ui.themes.IThemeManager
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.FileUtil
import com.itsaky.androidide.utils.VMUtils
import io.sentry.Sentry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.CliVirtualFileFinderFactory
import org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleFinder
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.JavaModuleGraph
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment.registerApplicationExtensionPoint
import org.jetbrains.kotlin.com.intellij.core.CoreFileTypeRegistry
import org.jetbrains.kotlin.com.intellij.lang.ParserDefinition
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.impl.SimpleDiagnosticsCollector
import org.jetbrains.kotlin.fir.FirSourceModuleData
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.deserialization.SingleModuleDataProvider
import org.jetbrains.kotlin.fir.pipeline.buildResolveAndCheckFirFromKtFiles
import org.jetbrains.kotlin.fir.session.FirJvmSessionFactory
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.java
import kotlin.system.exitProcess

/**
 * An [ApplicationLoader] which requires the credential protected storage
 * to be available initialization.
 *
 * Components that need to access the credential protected storage must be
 * initialized here.
 *
 * @author Akash Yadav
 */
internal object CredentialProtectedApplicationLoader : ApplicationLoader {
	private val logger = LoggerFactory.getLogger(CredentialProtectedApplicationLoader::class.java)

	private val _isLoaded = AtomicBoolean(false)
	private lateinit var application: IDEApplication

	var ideLogcatReader: IDELogcatReader? = null
		private set

	var pluginManager: PluginManager? = null
		private set

	val isLoaded: Boolean
		get() = _isLoaded.get()

	override suspend fun load(app: IDEApplication) {
		if (isLoaded) {
			logger.warn("Attempt to perform multiple loads of the application. Ignoring.")
			return
		}

		_isLoaded.set(true)

		logger.info("Loading credential protected storage context components...")
		application = app

		Environment.init(app)

		app.coroutineScope.launch {

			System.getProperties().forEach { (key, value) ->
				logger.info("prop: {}={}", key, value)
			}

			val targetName = "test"
			val source = """
                package com.itsaky.ktcompiler
                
                class Main {
                    fun main(args: Array<String>) {
                        println("Hello World!")
                    }
                }
            """.trimIndent()

			val filesDir = app.filesDir
			val sourceFile = filesDir.resolve(targetName)
			sourceFile.writeText(source)

			val ideaSystem = filesDir.resolve("idea-system")
			ideaSystem.mkdirs()

			val ideaConfigDir = ideaSystem.resolve("config")
			val ideaSystemDir = ideaSystem.resolve("system")

			ideaConfigDir.mkdirs()
			ideaSystemDir.mkdirs()

			System.setProperty("idea.config.path", ideaConfigDir.absolutePath)
			System.setProperty("idea.system.path", ideaSystemDir.absolutePath)

			System.setProperty(
				JavacConfigProvider.PROP_ANDROIDIDE_JAVA_HOME,
				Environment.JAVA_HOME.absolutePath
			)

			val firs = compileToFir(source, emptyList())
			logger.info("result: firs: {}", firs)
		}

		FeatureFlags.initialize()

		EventBus.getDefault().register(this)

		// Load termux application
		TermuxApplicationLoader.load(app)

		if (DevOpsPreferences.dumpLogs) {
			startLogcatReader()
		}

		withContext(Dispatchers.Main) {
			AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)

			if (IThemeManager.getInstance().getCurrentTheme() == IDETheme.MATERIAL_YOU) {
				DynamicColors.applyToActivitiesIfAvailable(app)
			}
		}

		initializePluginSystem()

		app.coroutineScope.launch(Dispatchers.IO) {
			// color schemes are stored in files
			// initialize scheme provider on the IO dispatcher
			IDEColorSchemeProvider.init()
		}

		if (!VMUtils.isJvm || VMUtils.isInstrumentedTest) {
			ToolsManager.init(app, null)
		}
	}

	fun compileToFir(
		source: String,
		classpathJars: List<File>,  // stdlib, etc.
	): List<FirFile> {
		val disposable = Disposer.newDisposable()

		val messageCollector = object : MessageCollector {
			override fun clear() = Unit
			override fun hasErrors() = false

			override fun report(
				severity: CompilerMessageSeverity,
				message: String,
				location: CompilerMessageSourceLocation?,
			) {
				Log.i("MessageCollector", "[${severity}] $message [$location]")
			}
		}

		val configuration = CompilerConfiguration().apply {
			put(CommonConfigurationKeys.MODULE_NAME, "my-module")
			put(CommonConfigurationKeys.USE_FIR, true)
			put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, LanguageVersionSettingsImpl.DEFAULT)
			addJvmClasspathRoots(classpathJars)
		}

		// 1. Core environment (handles PSI/IntelliJ plumbing)
		val appEnv = KotlinCoreApplicationEnvironment.create(disposable, unitTestMode = false)

		// Register .kt file type so PSI knows to produce KtFile, not PsiPlainTextFileImpl
		appEnv.registerFileType(KotlinFileType.INSTANCE, "kt")
		appEnv.registerFileType(KotlinFileType.INSTANCE, "kts")  // if you need scripts too
		appEnv.registerParserDefinition(KotlinParserDefinition())

		val coreProjectEnv = KotlinCoreProjectEnvironment(disposable, appEnv)

		// Add classpath JARs so the project knows about stdlib symbols
		classpathJars.forEach { coreProjectEnv.addJarToClassPath(it) }

		val project = coreProjectEnv.project

		project.registerService(
			JavaModuleResolver::class.java,
			CliJavaModuleResolver(
				moduleGraph = JavaModuleGraph(
					finder = CliJavaModuleFinder(
						jdkHome = Environment.JAVA_HOME,
						messageCollector = messageCollector,
						javaFileManager = KotlinCliJavaFileManagerImpl(
							myPsiManager = PsiManager.getInstance(project)
						),
						project = project,
						jdkRelease = 21,
					)
				),
				userModules = emptyList(),
				systemModules = emptyList(),
				project = project,
			)
		)

		project.registerService(
			VirtualFileFinderFactory::class.java,
			CliVirtualFileFinderFactory(
				index = JvmDependenciesIndexImpl(
					_roots = emptyList(),
					shouldOnlyFindFirstClass = true,
				),
				enableSearchInCtSym = true,
				perfManager = null,
			)
		)

		project.registerService(
			KotlinJavaPsiFacade::class.java,
			KotlinJavaPsiFacade(project)
		)

		val localFileSystem = VirtualFileManager.getInstance()
			.getFileSystem(StandardFileSystems.FILE_PROTOCOL)

		// 2. Wrap in VfsBasedProjectEnvironment — this IS an AbstractProjectEnvironment
		val projectEnvironment = VfsBasedProjectEnvironment(
			project = project,
			localFileSystem = localFileSystem,
			getPackagePartProviderFn = { scope ->
				// JvmPackagePartProvider reads package metadata from JAR manifests
				JvmPackagePartProvider(configuration.languageVersionSettings, scope).apply {
					addRoots(
						classpathJars.mapNotNull {
							localFileSystem.findFileByPath(it.absolutePath)
						}.map { file ->
							JavaRoot(
								file = file,
								type = JavaRoot.RootType.SOURCE,
								prefixFqName = null,
							)
						},
						messageCollector,
					)
				}
			}
		)

		val languageVersionSettings = configuration.languageVersionSettings
		val moduleName = Name.identifier("my-module")
		val platform = JvmPlatforms.defaultJvmPlatform

		// 3. Scopes
		val classpathScope =
			projectEnvironment.getSearchScopeByIoFiles(classpathJars, allowOutOfProjectRoots = true)
		val packagePartProvider = projectEnvironment.getPackagePartProvider(classpathScope)

		// 4. Shared library session
		val sharedLibrarySession = FirJvmSessionFactory.createSharedLibrarySession(
			mainModuleName = moduleName,
			projectEnvironment = projectEnvironment,
			extensionRegistrars = emptyList(),
			packagePartProvider = packagePartProvider,
			languageVersionSettings = languageVersionSettings,
			predefinedJavaComponents = null,
		)

		// 5. Library module + session
		val libraryModuleData = FirSourceModuleData(
			Name.identifier("my-module-libraries"),
			dependencies = emptyList(),
			dependsOnDependencies = emptyList(),
			friendDependencies = emptyList(),
			platform = platform,
		)

		val librarySession = FirJvmSessionFactory.createLibrarySession(
			sharedLibrarySession = sharedLibrarySession,
			moduleDataProvider = SingleModuleDataProvider(libraryModuleData),
			projectEnvironment = projectEnvironment,
			extensionRegistrars = emptyList(),
			scope = classpathScope,
			packagePartProvider = packagePartProvider,
			languageVersionSettings = languageVersionSettings,
			predefinedJavaComponents = null,
		)

		// 6. Source module — depends on the library module
		val sourceModuleData = FirSourceModuleData(
			moduleName,
			dependencies = listOf(libraryModuleData),
			dependsOnDependencies = emptyList(),
			friendDependencies = emptyList(),
			platform = platform,
		)

		// 7. Parse source into PSI
		val ktFile = KtPsiFactory(project, markGenerated = false)
			.createFile("inline.kt", source)
		val sourceScope = projectEnvironment.getSearchScopeByPsiFiles(listOf(ktFile))

		// 8. Source session
		val sourceSession = FirJvmSessionFactory.createSourceSession(
			moduleData = sourceModuleData,
			javaSourcesScope = sourceScope,
			projectEnvironment = projectEnvironment,
			createIncrementalCompilationSymbolProviders = { null },
			extensionRegistrars = emptyList(),
			configuration = configuration,
			predefinedJavaComponents = null,
			needRegisterJavaElementFinder = true,
			isForLeafHmppModule = true,
			init = {},
		)

		// 9. Resolve PSI → FIR
		val diagnostics = SimpleDiagnosticsCollector() { message, severity ->
			logger.info("compileToFir: [$severity] $message")
		}

		return buildResolveAndCheckFirFromKtFiles(
			session = sourceSession,
			ktFiles = listOf(ktFile),
			diagnosticsReporter = diagnostics
		).fir
	}


	fun handleUncaughtException(
		thread: Thread,
		exception: Throwable,
	) {
		writeException(exception)
		Sentry.captureException(exception)

		// schedule crash handler activity to be shown
		runCatching {
			val intent = Intent()
			intent.action = CrashHandlerActivity.REPORT_ACTION
			intent.putExtra(
				CrashHandlerActivity.TRACE_KEY,
				ThrowableUtils.getFullStackTrace(exception),
			)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			IDEApplication.instance.startActivity(intent)
		}.onFailure { error ->
			Sentry.captureException(error)
			logger.error("Unable to start crash handler activity", error)
		}

		// notify the original exception handler, if any
		IDEApplication.instance.uncaughtExceptionHandler?.uncaughtException(thread, exception)

		// finally, exit process
		exitProcess(EXIT_CODE_CRASH)
	}

	private fun writeException(throwable: Throwable?) =
		runCatching {
			// ignore errors
			File(FileUtil.getExternalStorageDir(), "idelog.txt")
				.writer()
				.buffered()
				.use { outputStream ->
					outputStream.write(ThrowableUtils.getFullStackTrace(throwable))
				}
		}

	private fun startLogcatReader() {
		if (ideLogcatReader != null) {
			// already started
			return
		}

		logger.info("Starting logcat reader...")
		ideLogcatReader = IDELogcatReader().also { it.start() }
	}

	private fun stopLogcatReader() {
		logger.info("Stopping logcat reader...")
		ideLogcatReader?.stop()
		ideLogcatReader = null
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun initializePluginSystem() {
		try {
			logger.info("Initializing plugin system...")

			// Create a plugin logger adapter
			val pluginLogger =
				object : PluginLogger {
					override val pluginId = "system"

					override fun debug(message: String) = logger.debug(message)

					override fun debug(
						message: String,
						error: Throwable,
					) = logger.debug(message, error)

					override fun info(message: String) = logger.info(message)

					override fun info(
						message: String,
						error: Throwable,
					) = logger.info(message, error)

					override fun warn(message: String) = logger.warn(message)

					override fun warn(
						message: String,
						error: Throwable,
					) = logger.warn(message, error)

					override fun error(message: String) = logger.error(message)

					override fun error(
						message: String,
						error: Throwable,
					) = logger.error(message, error)
				}

			pluginManager =
				PluginManager.getInstance(
					context = application,
					eventBus = EventBus.getDefault(),
					logger = pluginLogger,
				)

			// Set up plugin service providers
			setupPluginServices()

			// Load plugins asynchronously
			GlobalScope.launch {
				try {
					pluginManager?.loadPlugins()
					logger.info("Plugin system initialized successfully")
				} catch (e: Exception) {
					logger.error("Failed to load plugins", e)
				}
			}
		} catch (e: Exception) {
			Sentry.captureException(e)
			logger.error("Failed to initialize plugin system", e)
		}
	}

	/**
	 * Sets up the plugin service providers to integrate with AndroidIDE's actual systems.
	 */
	private fun setupPluginServices() {
		pluginManager?.let { manager ->
			manager.setActivityProvider { application.foregroundActivity }
			logger.info("Plugin services configured successfully")
		}
	}

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onPrefChanged(event: PreferenceChangeEvent) {
		val enabled = event.value as? Boolean?
		if (event.key == DevOpsPreferences.KEY_DEVOPTS_DEBUGGING_DUMPLOGS) {
			if (enabled == true) {
				startLogcatReader()
			} else {
				stopLogcatReader()
			}
		} else if (event.key == GeneralPreferences.UI_MODE && GeneralPreferences.uiMode != AppCompatDelegate.getDefaultNightMode()) {
			AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)
		} else if (event.key == GeneralPreferences.SELECTED_LOCALE) {
			// Use empty locale list if the locale has been reset to 'System Default'
			val selectedLocale = GeneralPreferences.selectedLocale
			val localeListCompat =
				selectedLocale?.let {
					LocaleListCompat.create(LocaleProvider.getLocale(selectedLocale))
				} ?: LocaleListCompat.getEmptyLocaleList()

			AppCompatDelegate.setApplicationLocales(localeListCompat)
		}
	}
}
