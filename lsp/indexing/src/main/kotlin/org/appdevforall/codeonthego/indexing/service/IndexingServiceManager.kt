package org.appdevforall.codeonthego.indexing.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

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

		/** The timeout duration for closing indexing services */
		private val SERVICE_CLOSE_TIMEOUT = 10.seconds
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
	 *
	 * Forwards the event to all registered services concurrently.
	 * Failures in one service don't affect others (SupervisorJob).
	 */
	fun onBuildCompleted() {
		if (!initialized) {
			log.warn("onBuildCompleted called before initialization, ignoring")
			return
		}
		scope.launch {
			services.values.forEach { service ->
				launch {
					try {
						service.onBuildCompleted()
					} catch (e: Exception) {
						log.error("Service '{}' failed in onBuildCompleted", service.id, e)
					}
				}
			}
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


		// Close services in reverse registration order
		val cancellationJobs = services.values.reversed().map { service ->
			scope.launch(Dispatchers.Default) {
				withTimeoutOrNull(SERVICE_CLOSE_TIMEOUT) {
					try {
						service.close()
						log.debug("Closed service: {}", service.id)
					} catch (e: Exception) {
						if (e is CancellationException) throw e
						log.error("Failed to close service: {}", service.id, e)
					}
				} ?: run {
					log.warn("Indexing service {} failed to close within timeout period: {}ms", service.id, SERVICE_CLOSE_TIMEOUT.inWholeMilliseconds)
				}
			}
		}

		val closeRegistryJob = scope.launch(Dispatchers.Default) {
			withTimeoutOrNull(SERVICE_CLOSE_TIMEOUT) {
				try {
					registry.close()
				} catch (e: Exception) {
					if (e is CancellationException) throw e
					log.error("Failed to close index registry")
				}
			} ?: run {
				log.warn("Index registry failed to close within timeout: {}ms", SERVICE_CLOSE_TIMEOUT.inWholeMilliseconds)
			}
		}

		scope.launch {
			runCatching { joinAll(closeRegistryJob, *cancellationJobs.toTypedArray()) }
				.onFailure { err ->
					log.error("Failed to close indexing services", err)
				}

			// Cancel in-flight work
			scope.coroutineContext.cancelChildren()
		}

		services.clear()
		initialized = false

		log.info("Indexing services shut down requested")
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