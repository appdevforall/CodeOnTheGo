package org.appdevforall.codeonthego.indexing.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of [IndexingService]s and the [IndexRegistry].
 */
class IndexingServiceManager(
	private val scope: CoroutineScope = CoroutineScope(
		SupervisorJob() + Dispatchers.Default
	),
) : Closeable {

	companion object {
		private val log = LoggerFactory.getLogger(IndexingServiceManager::class.java)
	}

	/**
	 * The central registry. All services register their indexes here.
	 * Consumers (LSPs, etc.) retrieve indexes from here.
	 */
	val registry = IndexRegistry()

	private val services = ConcurrentHashMap<String, IndexingService>()
	private var initialized = false

	/**
	 * Register an [IndexingService].
	 *
	 * Must be called before [onProjectSynced]. Services are initialized
	 * in registration order.
	 *
	 * @throws IllegalStateException if called after initialization.
	 */
	fun register(service: IndexingService) {
		check(!initialized) {
			"Cannot register services after initialization. " +
					"Register all services before the first onProjectSynced call."
		}

		if (services.putIfAbsent(service.id, service) != null) {
			log.warn("Attempt to re-register service with ID: {}", service.id)
			return
		}

		log.info("Registered indexing service: {}", service.id)
	}

	/**
	 * Called after project sync (e.g. Gradle sync) completes.
	 *
	 * On the first call, initializes all registered services
	 * (creates indexes, registers them). On subsequent calls,
	 * notifies services of the updated project model.
	 *
	 * Services process the event concurrently. Failures in one
	 * service don't affect others (SupervisorJob).
	 */
	fun onProjectSynced() {
		scope.launch {
			if (!initialized) {
				initializeServices()
				initialized = true
			}
		}
	}

	/**
	 * Called after a build completes.
	 */
	fun onBuildCompleted() {
		if (!initialized) {
			log.warn("onBuildCompleted called before initialization, ignoring")
			return
		}
	}

	/**
	 * Called when source files change.
	 */
	fun onSourceChanged() {
		if (!initialized) return
	}

	/**
	 * Returns the registered service with the given ID, or null.
	 */
	fun getService(id: String): IndexingService? =
		services[id]

	/**
	 * Returns all registered services.
	 */
	fun allServices(): List<IndexingService> =
		services.values.toList()

	/**
	 * Shut down all services and clear the registry.
	 */
	override fun close() {
		log.info("Shutting down indexing services")

		// Cancel in-flight work
		scope.coroutineContext.cancelChildren()

		// Close services in reverse registration order
		services.values.reversed().forEach { service ->
			try {
				service.close()
				log.debug("Closed service: {}", service.id)
			} catch (e: Exception) {
				log.error("Failed to close service: {}", service.id, e)
			}
		}

		services.clear()
		registry.close()
		initialized = false

		log.info("Indexing services shut down")
	}

	private suspend fun initializeServices() {
		log.info("Initializing {} indexing services", services.size)

		val allServices = allServices()
		for (service in allServices) {
			try {
				service.initialize(registry)
				log.info("Initialized service: {} (provides: {})",
					service.id,
					service.providedKeys.joinToString { it.name },
				)
			} catch (e: Exception) {
				log.error("Failed to initialize service: {}", service.id, e)
			}
		}

		// Verify all promised keys are registered
		for (service in allServices) {
			for (key in service.providedKeys) {
				if (!registry.isRegistered(key)) {
					log.warn(
						"Service '{}' promised index '{}' but did not register it",
						service.id, key.name,
					)
				}
			}
		}
	}
}