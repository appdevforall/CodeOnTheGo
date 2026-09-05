package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/**
 * The collector stands in for r8's `DiagnosticsHandler` through a JDK proxy, so it has to
 * answer every method that interface has - including ones it was not written for.
 *
 * These drive it through fake interfaces rather than r8's, because r8 loads through
 * [DexTool]'s private class loader and a real d8 run needs a host toolchain.
 */
class D8DiagnosticsCollectorTest {
	/** Stands in for the shape of r8's handler: void reports, and a level the handler may change. */
	interface FakeHandler {
		fun error(diagnostic: Any)

		fun warning(diagnostic: Any)

		fun modifyDiagnosticsLevel(
			level: Any,
			diagnostic: Any,
		): Any
	}

	/** The method shape the pass-through arm cannot answer: a primitive return. */
	interface FakeHandlerWithPrimitive {
		fun retryLimit(diagnostic: Any): Int
	}

	private fun <T> proxy(
		type: Class<T>,
		collector: DexTool.D8DiagnosticsCollector,
	): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), collector))

	@Test
	fun `a reported error is collected and a proposed level is kept`() {
		val collector = DexTool.D8DiagnosticsCollector()
		val handler = proxy(FakeHandler::class.java, collector)

		handler.warning("dropped")
		val level = handler.modifyDiagnosticsLevel("WARNING", "some diagnostic")

		assertThat(level).isEqualTo("WARNING")
		assertThat(collector.errors).isEmpty()
	}

	/**
	 * The regression: a primitive-returning method the collector has no arm for must fail
	 * with a message naming it, not return null.
	 *
	 * `isInstance` is false for every primitive type, so such a method falls through to the
	 * pass-through arm and finds no argument that fits. Returning null there hands the proxy
	 * a null to unbox, and the resulting NullPointerException is raised inside d8's own call
	 * and reads as a d8 bug. Goes red if the arm returns null again.
	 */
	@Test
	fun `a method the collector cannot answer fails by name rather than returning null`() {
		val collector = DexTool.D8DiagnosticsCollector()
		val handler = proxy(FakeHandlerWithPrimitive::class.java, collector)

		// A RuntimeException travels out of a proxy call untouched, so this reaches the caller
		// as itself rather than wrapped.
		val thrown = assertThrows<IllegalStateException> { handler.retryLimit("some diagnostic") }

		assertThat(thrown).hasMessageThat().contains("retryLimit")
		assertThat(thrown).hasMessageThat().contains("int")
	}

	@Test
	fun `Object's own methods are answered rather than passed through`() {
		val collector = DexTool.D8DiagnosticsCollector()
		val handler = proxy(FakeHandler::class.java, collector)

		assertThat(handler.toString()).isEqualTo("D8DiagnosticsCollector")
		assertThat(handler).isEqualTo(handler)
		assertThat(handler.hashCode()).isEqualTo(System.identityHashCode(handler))
	}
}
