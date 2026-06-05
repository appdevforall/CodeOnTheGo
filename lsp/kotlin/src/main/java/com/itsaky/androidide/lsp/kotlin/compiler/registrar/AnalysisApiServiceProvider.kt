package com.itsaky.androidide.lsp.kotlin.compiler.registrar

import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager
import kotlin.reflect.KClass

internal typealias ServiceMap = Map<KClass<*>, ServiceRegistration<*>>
internal typealias MutableServiceMap = MutableMap<KClass<*>, ServiceRegistration<*>>

internal sealed class ServiceRegistration<T : Any> {

	companion object {
		operator fun <T : Any> invoke(klass: KClass<T>, type: KClass<out T>) =
			Typed(klass, type)

		operator fun <T : Any> invoke(klass: KClass<T>, instance: T) =
			Instance(klass, instance)

		operator fun <T : Any> invoke(klass: KClass<T>, factory: () -> T) =
			InstanceFactory(klass, factory)
	}

	abstract val klass: KClass<T>

	abstract fun register(to: MockComponentManager)

	data class Typed<T : Any>(override val klass: KClass<T>, val type: KClass<out T>) :
		ServiceRegistration<T>() {
		override fun register(to: MockComponentManager) {
			to.registerService(klass.java, type.java)
		}
	}

	data class Instance<T : Any>(override val klass: KClass<T>, val instance: T) :
		ServiceRegistration<T>() {
		override fun register(to: MockComponentManager) {
			to.registerService(klass.java, instance)
		}
	}

	data class InstanceFactory<T : Any>(override val klass: KClass<T>, val factory: () -> T) :
		ServiceRegistration<T>() {
		override fun register(to: MockComponentManager) {
			val instance = factory()
			to.registerService(klass.java, instance)
		}
	}
}

internal interface AnalysisApiServiceProvider {
	val pluginRelativePath: String?

	val applicationServices: ServiceMap
	val projectServices: ServiceMap

	fun toBuilder(): Builder = Builder(
		pluginRelativePath = pluginRelativePath,
		appServices = applicationServices,
		projectServices = projectServices,
	)

	private data class SimpleAnalysisApiServiceProvider(
		override val pluginRelativePath: String?,
		override val applicationServices: ServiceMap,
		override val projectServices: ServiceMap,
	) : AnalysisApiServiceProvider

	class Builder(
		var pluginRelativePath: String? = null,
		appServices: ServiceMap = emptyMap(),
		projectServices: ServiceMap = emptyMap(),
	) {
		private val applicationServices: MutableServiceMap = mutableMapOf()
		private val projectServices: MutableServiceMap = mutableMapOf()

		init {
			appServices.forEach { (klass, either) -> appSvc(klass, either, false) }
			projectServices.forEach { (klass, either) -> projSvc(klass, either, false) }
		}

		private fun put(
			store: MutableServiceMap,
			key: KClass<*>,
			value: ServiceRegistration<*>,
			replace: Boolean
		) {
			if (!replace) {
				check(store.putIfAbsent(key, value) == null) {
					"Service $key already registered"
				}
			} else {
				check(store.replace(key, value) != null) {
					"Service $key not found"
				}
			}
		}

		private fun appSvc(
			key: KClass<*>, value: ServiceRegistration<*>, replace: Boolean = false
		) = put(applicationServices, key, value, replace)

		private fun projSvc(
			key: KClass<*>, value: ServiceRegistration<*>, replace: Boolean = false
		) = put(projectServices, key, value, replace)

		fun <T : Any> appService(
			key: KClass<T>,
			type: KClass<out T> = key,
			replace: Boolean = false,
		) = appSvc(key, ServiceRegistration(key, type), replace)

		fun <T : Any> appService(key: KClass<T>, value: T, replace: Boolean = false) =
			appSvc(key, ServiceRegistration(key, value), replace)

		fun <T : Any> appService(key: KClass<T>, replace: Boolean = false, value: () -> T) =
			appSvc(key, ServiceRegistration(key, value), replace)

		fun <T : Any> projectService(
			key: KClass<T>,
			type: KClass<out T> = key,
			replace: Boolean = false,
		) = projSvc(key, ServiceRegistration(key, type), replace)

		fun <T : Any> projectService(key: KClass<T>, value: T, replace: Boolean = false) =
			projSvc(key, ServiceRegistration(key, value), replace)

		fun <T : Any> projectService(key: KClass<T>, replace: Boolean = false, value: () -> T) =
			projSvc(key, ServiceRegistration(key, value), replace)

		fun build(): AnalysisApiServiceProvider = SimpleAnalysisApiServiceProvider(
			pluginRelativePath = pluginRelativePath,
			applicationServices = applicationServices.toMap(),
			projectServices = projectServices.toMap(),
		)
	}
}
