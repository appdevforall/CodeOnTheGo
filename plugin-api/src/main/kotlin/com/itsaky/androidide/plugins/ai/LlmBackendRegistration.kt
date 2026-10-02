package com.itsaky.androidide.plugins.ai

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * Keeps one backend plugin's [LlmInferenceService.LlmBackend] registered with the router, and tells
 * the router when the backend's settings change.
 *
 * Every backend plugin needs the same wiring, and the copies had begun to drift: plugins activate
 * in parallel with no ordering, so the router may not exist yet when [start] runs, and it goes away
 * and comes back whenever its provider is restarted. This listens for the provider's lifecycle and
 * re-registers, and it watches the plugin's own settings file for the keys that change what the
 * backend reports, so a consumer such as the agent's backend tag re-reads it.
 *
 * One instance per plugin. [start] and [stop] pair with the plugin's `activate` and `deactivate`;
 * [stop] is idempotent, so calling it again from `dispose` is safe.
 *
 * @param context the backend plugin's own context
 * @param preferences the backend's settings file, read when [start] runs
 * @param watchedKeys the settings that change what the backend's `isAvailable`, model name or status
 *   answers. A `clear()` of the file always counts.
 * @param onSettingChanged called for every change to [preferences], after the router was told of a
 *   watched one, so a backend can react to a key of its own - re-checking a server, say
 * @param onRegistered called after each successful registration, the first and every one following
 *   a restart of the router's provider
 * @param providerPluginId the plugin that publishes the router
 */
class LlmBackendRegistration(
	private val context: PluginContext,
	private val preferences: () -> SharedPreferences,
	private val watchedKeys: Set<String>,
	private val onSettingChanged: (key: String?) -> Unit = {},
	private val onRegistered: () -> Unit = {},
	private val providerPluginId: String = AI_CORE_PLUGIN_ID,
) {
	companion object {
		/** The plugin that publishes [LlmInferenceService]. */
		const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
	}

	private val lock = Any()

	@Volatile
	private var backend: LlmInferenceService.LlmBackend? = null

	/** Whether the backend is registered with the router now. */
	@Volatile
	var isRegistered: Boolean = false
		private set

	/** Re-registers when the router's provider activates; its deactivation took the registration with it. */
	private val providerLifecycle =
		object : PluginLifecycleListener {
			override fun onPluginActivated(pluginId: String) {
				if (pluginId == providerPluginId) register()
			}

			override fun onPluginDeactivated(pluginId: String) {
				if (pluginId == providerPluginId) isRegistered = false
			}

			override fun onPluginUninstalled(pluginId: String) {
				if (pluginId == providerPluginId) isRegistered = false
			}
		}

	/** A field, not a lambda at the call: SharedPreferences holds its listeners weakly. A null key is `clear()`. */
	private val settingsWatch =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == null || key in watchedKeys) notifyBackendChanged()
			onSettingChanged(key)
		}

	/**
	 * Starts keeping [backend] registered: watches the settings and the router's provider, then
	 * registers if the router is already there. Replaces a backend from an earlier [start].
	 *
	 * @param backend the backend this plugin contributes
	 * @return true when it is registered now, false when it will be once the router appears
	 */
	fun start(backend: LlmInferenceService.LlmBackend): Boolean {
		stop()
		this.backend = backend
		preferences().registerOnSharedPreferenceChangeListener(settingsWatch)
		// Listen first, then try: a listener added after a successful attempt would still be needed
		// for a later restart of the provider, and one added before costs nothing.
		context.addPluginLifecycleListener(providerLifecycle)
		val registered = register()
		if (!registered) {
			context.logger.info("LlmBackendRegistration: router not active yet; will register '${backend.id}' when it activates")
		}
		return registered
	}

	/**
	 * Unregisters the backend and stops watching. Does not close the backend, which stays the
	 * plugin's to release. Idempotent.
	 */
	fun stop() {
		runCatching { context.removePluginLifecycleListener(providerLifecycle) }
		runCatching { preferences().unregisterOnSharedPreferenceChangeListener(settingsWatch) }
		synchronized(lock) {
			val current = backend ?: return
			backend = null
			if (!isRegistered) return
			isRegistered = false
			try {
				resolveService()?.unregisterBackend(current.id)
				context.logger.info("LlmBackendRegistration: unregistered '${current.id}'")
			} catch (e: Exception) {
				context.logger.error("LlmBackendRegistration: could not unregister '${current.id}'", e)
			}
		}
	}

	/**
	 * Tells the router the backend's availability, model or status changed, if it is registered.
	 * Guarded against [Throwable]: a router too old to have the method must not break a save.
	 */
	fun notifyBackendChanged() {
		val current = backend ?: return
		if (!isRegistered) return
		try {
			resolveService()?.notifyBackendChanged(current.id)
		} catch (e: Throwable) {
			context.logger.debug("LlmBackendRegistration: could not report a change to '${current.id}': ${e.message}")
		}
	}

	/** @return true when the backend is registered, now or already */
	private fun register(): Boolean {
		synchronized(lock) {
			if (isRegistered) return true
			val current = backend ?: return false
			val service = resolveService() ?: return false
			try {
				service.registerBackend(current)
			} catch (e: Exception) {
				context.logger.error("LlmBackendRegistration: could not register '${current.id}'", e)
				return false
			}
			isRegistered = true
			context.logger.info("LlmBackendRegistration: registered '${current.id}'")
		}
		// Outside the lock: the callback may call back into notifyBackendChanged.
		onRegistered()
		return true
	}

	/**
	 * The router, preferring the process-global registry and falling back to the provider-scoped
	 * lookup, so a registry cleared by another plugin is not fatal.
	 */
	private fun resolveService(): LlmInferenceService? =
		try {
			SharedServices.get(LlmInferenceService::class.java)
				?: context.getPluginService(providerPluginId, LlmInferenceService::class.java)
		} catch (e: Exception) {
			context.logger.warn("LlmBackendRegistration: could not resolve LlmInferenceService: ${e.message}")
			null
		}
}
