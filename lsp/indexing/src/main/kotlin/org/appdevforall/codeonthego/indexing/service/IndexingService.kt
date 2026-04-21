package org.appdevforall.codeonthego.indexing.service
import java.io.Closeable

/**
 * A service that knows how to build and maintain an index for a
 * specific domain.
 *
 * Implementations should be stateless with respect to the project
 * model because they receive it as a parameter, not as a constructor
 * argument. This allows the same service instance to handle
 * re-syncs without recreation.
 */
interface IndexingService : Closeable {

	/**
	 * Unique identifier for this service.
	 * Used for logging and debugging.
	 */
	val id: String

	/**
	 * The keys of the indexes this service registers.
	 * Used by the manager to verify all expected indexes
	 * are available after initialization.
	 */
	val providedKeys: List<IndexKey<*>>

	/**
	 * Called once after the service is registered.
	 *
	 * Create your index instances here and register them
	 * with the [registry].
	 */
	suspend fun initialize(registry: IndexRegistry)

	/**
	 * Called after a build completes.
	 *
	 * Implementations should re-index any build outputs that may have changed
	 * (e.g. generated JARs). The default is a no-op.
	 */
	suspend fun onBuildCompleted() {}

	/**
	 * Called when the project is closed or the IDE shuts down.
	 * Release all resources.
	 */
	override fun close() {}
}