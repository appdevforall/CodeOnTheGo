package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.manager.core.PluginManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference

class PluginResourceContext(
	baseContext: Context,
	private val pluginId: String,
	private var pluginResources: Resources,
	private val pluginPackageInfo: PackageInfo? = null,
	private val pluginClassLoader: ClassLoader? = null,
) : ContextThemeWrapper(baseContext, 0) {
	companion object {
		private const val TAG = "PluginResourceContext"
		private const val PLUGIN_THEME_NAME = "PluginTheme"
		private val ADD_ASSET_PATH_METHOD = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
	}

	private var inflater: LayoutInflater? = null
	private var pluginTheme: Theme? = null
	private var lastActivityTheme: Theme? = null
	private var lastNightMode: Int = -1
	private var usesCustomPackageId = false
	private val pluginSourceDir: String? = pluginPackageInfo?.applicationInfo?.sourceDir
	private val appThemeResId: Int = pluginPackageInfo?.applicationInfo?.theme ?: 0
	private var pluginThemeResId: Int = 0
	private var pluginThemeResIdResolved = false

	init {
		usesCustomPackageId = detectCustomPackageId()
	}

	private fun detectCustomPackageId(): Boolean {
		val cl = pluginClassLoader ?: return false
		val pkg = pluginPackageInfo?.packageName ?: return false
		return try {
			val rClass = cl.loadClass("$pkg.R")
			rClass.declaredClasses
				.asSequence()
				.flatMap { it.declaredFields.asSequence() }
				.filter { it.type == Int::class.javaPrimitiveType }
				.map { it.getInt(null) }
				.filter { it != 0 }
				.any { (it ushr 24) != 0x7F }
		} catch (e: ReflectiveOperationException) {
			Log.w(TAG, "Failed to detect custom package ID for $pkg", e)
			false
		}
	}

	fun usesCustomPackageId(): Boolean = usesCustomPackageId

	private var recreatedAssetManager: AssetManager? = null

	private fun recreatePluginResources(newConfig: Configuration) {
		val sourceDir = pluginSourceDir ?: return
		val oldAssetManager = recreatedAssetManager

		@Suppress("DEPRECATION")
		val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
		addAssetPathFallback(assetManager, sourceDir)
		@Suppress("DEPRECATION")
		pluginResources = Resources(assetManager, baseContext.resources.displayMetrics, newConfig)
		recreatedAssetManager = assetManager
		oldAssetManager?.close()
	}

	// The activity's Resources only: each copy gets the loader once, when it is built.
	private var patchedResources: Resources? = null
	private var cachedLoader: ResourcesLoader? = null
	private var cachedProvider: ResourcesProvider? = null

	private fun ensurePluginResourcesAdded(targetResources: Resources) {
		if (patchedResources === targetResources) return
		addPluginResources(targetResources)
		patchedResources = targetResources
	}

	@Suppress("NewApi")
	private fun addPluginResources(targetResources: Resources) {
		val sourceDir = pluginSourceDir ?: return

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			try {
				if (cachedLoader == null) {
					ParcelFileDescriptor.open(File(sourceDir), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
						cachedProvider = ResourcesProvider.loadFromApk(pfd)
						cachedLoader = ResourcesLoader().also { it.addProvider(cachedProvider!!) }
					}
				}
				targetResources.addLoaders(cachedLoader!!)
				Log.d(TAG, "Added plugin resources via ResourcesLoader for ${pluginPackageInfo?.packageName}")
			} catch (e: FileNotFoundException) {
				Log.e(TAG, "ResourcesLoader failed, falling back to addAssetPath", e)
				addAssetPathFallback(targetResources.assets, sourceDir)
			} catch (e: IOException) {
				Log.e(TAG, "ResourcesLoader failed, falling back to addAssetPath", e)
				addAssetPathFallback(targetResources.assets, sourceDir)
			}
		} else {
			addAssetPathFallback(targetResources.assets, sourceDir)
		}
	}

	private fun addAssetPathFallback(
		assets: AssetManager,
		pluginSourceDir: String,
	) {
		try {
			ADD_ASSET_PATH_METHOD.invoke(assets, pluginSourceDir)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to add plugin asset path", e)
		}
	}

	private var hostResourcesOwner: WeakReference<Context>? = null
	private var hostResourcesConfig: Configuration? = null
	private var hostResources: Resources? = null

	/**
	 * The Resources this plugin's views are built on. On Android 11+ that is a copy of [actCtx]'s,
	 * pinned to its current configuration and never changed once handed out: built on the activity's
	 * own, which addLoaders had just rebuilt, a tab's first views drew sp text at about half size
	 * (ADFA-7142). The activity's Resources still get the loader, and first, because plugins also
	 * resolve their ids through the activity, as AI Core's ChatFragment does with Fragment.getString
	 * and ContextThemeWrapper(requireContext(), R.style.PluginTheme). Before R there are no loaders,
	 * and the activity's assets are patched in place.
	 */
	private fun pluginResourcesFor(actCtx: Context): Resources {
		ensurePluginResourcesAdded(actCtx.resources)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return actCtx.resources
		val config = actCtx.resources.configuration
		val cached = hostResources
		if (cached != null && hostResourcesOwner?.get() === actCtx && hostResourcesConfig == config) return cached

		val pinned = Configuration(config)
		val res = actCtx.createConfigurationContext(pinned).resources
		addPluginResources(res)
		hostResourcesOwner = WeakReference(actCtx)
		hostResourcesConfig = pinned
		hostResources = res
		val metrics = res.displayMetrics
		@Suppress("DEPRECATION")
		Log.d(
			TAG,
			"Resources for $pluginId: fontScale=${res.configuration.fontScale} " +
				"density=${metrics.density} scaledDensity=${metrics.scaledDensity}",
		)
		return res
	}

	private fun resolveActivityContextAndTheme(): Pair<Context, Theme>? {
		val fragmentCtx = PluginFragmentHelper.getCurrentActivityContext(pluginId)
		val fragmentTheme = PluginFragmentHelper.getCurrentActivityTheme(pluginId)
		if (fragmentCtx != null && fragmentTheme != null) {
			return fragmentCtx to fragmentTheme
		}
		val activity = PluginManager.getInstance()?.getCurrentActivity() ?: return null
		return activity to activity.theme
	}

	override fun getResources(): Resources {
		if (!usesCustomPackageId) return pluginResources

		val (actCtx, _) = resolveActivityContextAndTheme() ?: return pluginResources
		return pluginResourcesFor(actCtx)
	}

	// Never the activity's: every plugin with its own package id adds its APK to those, and asset
	// paths carry no package id, so a path two plugins both ship opens whichever was added last.
	override fun getAssets(): AssetManager = pluginResources.assets

	override fun getTheme(): Theme {
		if (!usesCustomPackageId) {
			val currentNightMode = baseContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
			if (pluginTheme == null || lastNightMode != currentNightMode) {
				lastNightMode = currentNightMode
				inflater = null
				recreatePluginResources(baseContext.resources.configuration)
				if (!pluginThemeResIdResolved) {
					pluginThemeResId =
						pluginResources.getIdentifier(
							PLUGIN_THEME_NAME,
							"style",
							pluginPackageInfo?.packageName,
						)
					pluginThemeResIdResolved = true
				}
				val themeResId =
					when {
						pluginThemeResId != 0 -> pluginThemeResId
						appThemeResId != 0 -> appThemeResId
						currentNightMode == Configuration.UI_MODE_NIGHT_YES -> android.R.style.Theme_Material
						else -> android.R.style.Theme_Material_Light
					}
				pluginTheme =
					pluginResources.newTheme().apply {
						applyStyle(themeResId, true)
					}
			}
			return pluginTheme!!
		}

		val (actCtx, actTheme) = resolveActivityContextAndTheme() ?: return pluginTheme ?: baseContext.theme
		val res = pluginResourcesFor(actCtx)
		if (pluginTheme?.resources !== res || lastActivityTheme !== actTheme) {
			lastActivityTheme = actTheme
			inflater = null

			if (!pluginThemeResIdResolved) {
				pluginThemeResId =
					res.getIdentifier(
						PLUGIN_THEME_NAME,
						"style",
						pluginPackageInfo?.packageName,
					)
				pluginThemeResIdResolved = true
			}

			pluginTheme =
				res.newTheme().apply {
					setTo(actTheme)

					if (appThemeResId != 0) {
						applyStyle(appThemeResId, false)
					}

					if (pluginThemeResId != 0) {
						applyStyle(pluginThemeResId, true)
					}
				}
		}
		return pluginTheme ?: baseContext.theme
	}

	override fun getClassLoader(): ClassLoader = pluginClassLoader ?: baseContext.classLoader

	override fun getPackageName(): String = pluginPackageInfo?.packageName ?: super.getPackageName()

	override fun getApplicationInfo(): ApplicationInfo = pluginPackageInfo?.applicationInfo ?: super.getApplicationInfo()

	override fun getSystemService(name: String): Any? {
		if (Context.LAYOUT_INFLATER_SERVICE == name) {
			if (inflater == null) {
				if (!usesCustomPackageId) {
					val baseInflater = LayoutInflater.from(baseContext)
					inflater =
						object : LayoutInflater(baseInflater, this@PluginResourceContext) {
							override fun cloneInContext(newContext: Context): LayoutInflater =
								this@PluginResourceContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

							override fun onCreateView(
								viewName: String,
								attrs: AttributeSet,
							): View? {
								if (viewName.indexOf('.') == -1) {
									for (prefix in arrayOf("android.widget.", "android.view.", "android.webkit.")) {
										try {
											return createView(viewName, prefix, attrs)
										} catch (_: ClassNotFoundException) {
										}
									}
								}
								return super.onCreateView(viewName, attrs)
							}
						}
				} else {
					inflater = LayoutInflater.from(baseContext).cloneInContext(this)
				}
			}
			return inflater
		}
		return super.getSystemService(name)
	}

	fun getPluginPackageInfo(): PackageInfo? = pluginPackageInfo

	fun getResourceId(
		name: String,
		type: String,
	): Int = getResources().getIdentifier(name, type, packageName)

	fun inflateLayout(
		layoutResId: Int,
		root: android.view.ViewGroup? = null,
		attachToRoot: Boolean = false,
	): android.view.View {
		val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
		return inflater.inflate(layoutResId, root, attachToRoot)
	}
}
