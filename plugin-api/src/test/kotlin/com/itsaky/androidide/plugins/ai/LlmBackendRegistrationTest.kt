package com.itsaky.androidide.plugins.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Robolectric for a real [SharedPreferences]: the settings watch is the half of the wiring that
 * the backend tag depends on. The router and the context are proxies that record what was asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmBackendRegistrationTest {
	private lateinit var prefs: SharedPreferences
	private val calls = mutableListOf<String>()
	private val lifecycle = mutableListOf<PluginLifecycleListener>()
	private val changedKeys = mutableListOf<String?>()
	private var registrations = 0

	private val router = proxy<LlmInferenceService> { name, args -> calls += "$name:${args?.firstOrNull()?.let(::idOf)}" }
	private val backend = proxy<LlmInferenceService.LlmBackend> { name, _ -> if (name == "getId") ID else null }
	private val logger = proxy<PluginLogger> { _, _ -> null }
	private val context =
		proxy<PluginContext> { name, args ->
			when (name) {
				"getLogger" -> logger
				"addPluginLifecycleListener" -> lifecycle += args!![0] as PluginLifecycleListener
				"removePluginLifecycleListener" -> lifecycle -= args!![0] as PluginLifecycleListener
				else -> null
			}
		}

	private lateinit var registration: LlmBackendRegistration

	@Before
	fun setUp() {
		prefs = RuntimeEnvironment.getApplication().getSharedPreferences("backend", Context.MODE_PRIVATE)
		prefs.edit().clear().commit()
		SharedServices.unregister(LlmInferenceService::class.java)
		registration =
			LlmBackendRegistration(
				context = context,
				preferences = { prefs },
				watchedKeys = setOf(KEY_MODEL),
				onSettingChanged = { changedKeys += it },
				onRegistered = { registrations++ },
			)
	}

	@After
	fun tearDown() {
		registration.stop()
		SharedServices.unregister(LlmInferenceService::class.java)
	}

	@Test
	fun `Given_the_router_is_up_When_started_Then_the_backend_is_registered_once`() {
		SharedServices.register(LlmInferenceService::class.java, router)

		assertThat(registration.start(backend)).isTrue()

		assertThat(calls).containsExactly("registerBackend:$ID")
		assertThat(registrations).isEqualTo(1)
	}

	@Test
	fun `Given_no_router_yet_When_its_provider_activates_Then_the_backend_registers_then`() {
		assertThat(registration.start(backend)).isFalse()
		SharedServices.register(LlmInferenceService::class.java, router)

		lifecycle.toList().forEach { it.onPluginActivated(LlmBackendRegistration.AI_CORE_PLUGIN_ID) }

		assertThat(registration.isRegistered).isTrue()
		assertThat(calls).containsExactly("registerBackend:$ID")
	}

	@Test
	fun `Given_a_registered_backend_When_the_provider_restarts_Then_it_registers_again`() {
		SharedServices.register(LlmInferenceService::class.java, router)
		registration.start(backend)

		lifecycle.toList().forEach { it.onPluginDeactivated(LlmBackendRegistration.AI_CORE_PLUGIN_ID) }
		lifecycle.toList().forEach { it.onPluginActivated(LlmBackendRegistration.AI_CORE_PLUGIN_ID) }

		assertThat(calls).containsExactly("registerBackend:$ID", "registerBackend:$ID")
		assertThat(registrations).isEqualTo(2)
	}

	@Test
	fun `Given_a_registered_backend_When_a_watched_key_changes_Then_the_router_is_told`() {
		SharedServices.register(LlmInferenceService::class.java, router)
		registration.start(backend)

		prefs.edit().putString(KEY_MODEL, "qwen3").commit()
		prefs.edit().putString(KEY_OTHER, "x").commit()

		assertThat(calls).containsExactly("registerBackend:$ID", "notifyBackendChanged:$ID")
		assertThat(changedKeys).containsExactly(KEY_MODEL, KEY_OTHER).inOrder()
	}

	@Test
	fun `Given_a_registered_backend_When_stopped_Then_it_unregisters_and_stops_listening`() {
		SharedServices.register(LlmInferenceService::class.java, router)
		registration.start(backend)

		registration.stop()
		registration.stop()
		prefs.edit().putString(KEY_MODEL, "qwen3").commit()

		assertThat(calls).containsExactly("registerBackend:$ID", "unregisterBackend:$ID")
		assertThat(lifecycle).isEmpty()
		assertThat(changedKeys).isEmpty()
	}

	private fun idOf(arg: Any): Any = if (arg is LlmInferenceService.LlmBackend) arg.id else arg

	private companion object {
		const val ID = "test-backend"
		const val KEY_MODEL = "model"
		const val KEY_OTHER = "unrelated"

		inline fun <reified T> proxy(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
			Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
				when (method.name) {
					"hashCode" -> System.identityHashCode(self)
					"equals" -> self === args?.get(0)
					"toString" -> T::class.java.simpleName
					else -> answer(method.name, args).takeUnless { it == Unit }
				}
			} as T
	}
}
