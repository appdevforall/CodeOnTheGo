package com.itsaky.androidide.editor.schemes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class IDEColorSchemeLoadTest {
	private fun newScheme() = IDEColorScheme(File("./src/test/resources/syntax.json"), "test").apply { name = "Test" }

	@Test
	fun `loading a scheme again does not parse it again`() {
		val scheme = newScheme()
		scheme.load()
		val colorsAfterFirstLoad = scheme.colorIds.size

		scheme.load()

		assertThat(scheme.colorIds.size).isEqualTo(colorsAfterFirstLoad)
	}

	@Test
	fun `concurrent loads of one scheme leave every style with a valid colour`() {
		repeat(ROUNDS) {
			val scheme = newScheme()
			val failures = Collections.synchronizedList(mutableListOf<Throwable>())
			val start = CountDownLatch(1)
			val loaders =
				List(THREADS) {
					thread(isDaemon = true) {
						start.await()
						runCatching { scheme.load() }.onFailure(failures::add)
					}
				}
			start.countDown()
			loaders.forEach { it.join(LOAD_TIMEOUT_MS) }

			assertThat(loaders.filter { it.isAlive }).isEmpty()
			assertThat(failures).isEmpty()
			scheme.languages.values.distinct().forEach { language ->
				language.styles.values.forEach { style -> style.makeStyle() }
			}
		}
	}

	private companion object {
		const val ROUNDS = 200
		const val THREADS = 8
		const val LOAD_TIMEOUT_MS = 10_000L
	}
}
