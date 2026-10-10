package org.appdevforall.codeonthego.indexing.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
	private val scope: CoroutineScope =
		CoroutineScope(
			SupervisorJob() + Dispatchers.Default,
		),
) : Closeable {
	companion object {
		private val log = LoggerFactory.getLogger(IndexingServiceManager::class.java)

		/** The timeout duration for closing indexing services */
		private val SERVICE_CLOSE_TIMEOUT = 10.seconds

		private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

		/** The most recent background [close], which the next manager waits for. */
		@Volatile
		private var pendingClose: Job? = null

		/** Suspends until every [close] issued so far has finished. */
		suspend fun awaitPendingClose() {
			pendingClose?.join()
		}
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
	 * Must be called before [onProjectSynced]. Services are initialized in an
	 * unspecified order (they are held in a [ConcurrentHashMap]), so a service's
	 * [IndexingService.initialize] must not depend on another service having
	 * been initialized first.
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
	fun getService(id: String): IndexingService? = services[id]

	/**
	 * Returns all registered services.
	 */
	fun allServices(): List<IndexingService> = services.values.toList()

	/**
	 * Shut down all services and clear the registry.
	 */
	override fun close() {
		log.info("Shutting down indexing services")

		// Called from ProjectHandlerActivity.onPause on the main thread, so the
		// closes (bounded per service by SERVICE_CLOSE_TIMEOUT, but SQLiteIndex and
		// BackgroundIndexer block inside them) run in the background instead of
		// parking main into an ANR. The next manager's initializeServices() waits
		// for this job, so two managers never hold the same index databases.
		val toClose = services.values.toList()
		services.clear()
		initialized = false

		val previous = pendingClose
		pendingClose =
			closeScope.launch {
				previous?.join()

				// Services are closed concurrently (so no ordering is implied) and
				// failures are isolated per service.
				val serviceJobs =
					toClose.map { service ->
						launch {
							withTimeoutOrNull(SERVICE_CLOSE_TIMEOUT) {
								try {
									service.close()
									log.debug("Closed service: {}", service.id)
								} catch (e: Exception) {
									if (e is CancellationException) throw e
									log.error("Failed to close service: {}", service.id, e)
								}
							} ?: log.warn(
								"Indexing service {} failed to close within timeout period: {}ms",
								service.id,
								SERVICE_CLOSE_TIMEOUT.inWholeMilliseconds,
							)
						}
					}

				val closeRegistryJob =
					launch {
						withTimeoutOrNull(SERVICE_CLOSE_TIMEOUT) {
							try {
								registry.close()
							} catch (e: Exception) {
								if (e is CancellationException) throw e
								log.error("Failed to close index registry", e)
							}
						} ?: log.warn(
							"Index registry failed to close within timeout: {}ms",
							SERVICE_CLOSE_TIMEOUT.inWholeMilliseconds,
						)
					}

				joinAll(*serviceJobs.toTypedArray(), closeRegistryJob)

				// Cancel any in-flight indexing work still running on the manager scope.
				scope.coroutineContext.cancelChildren()

				log.info("Indexing services shut down")
			}
	}

	private suspend fun initializeServices() {
		pendingClose?.join()
		log.info("Initializing {} indexing services", services.size)

		val allServices = allServices()
		for (service in allServices) {
			try {
				service.initialize(registry)
				log.info(
					"Initialized service: {} (provides: {})",
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
						service.id,
						key.name,
					)
				}
			}
		}
	}
}
