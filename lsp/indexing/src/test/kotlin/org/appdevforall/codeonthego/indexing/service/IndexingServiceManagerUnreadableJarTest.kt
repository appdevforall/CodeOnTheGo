package org.appdevforall.codeonthego.indexing.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * [IndexingServiceManager.filterNewlyUnreadableJars] dedupes an unreadable JAR path across every
 * call for the life of one manager, so a JAR that stays unreadable across many indexing passes is
 * reported once rather than once per pass - and reports it again once a new project session starts
 * a new manager (or this one is [closed][IndexingServiceManager.close] and reused).
 */
@RunWith(JUnit4::class)
class IndexingServiceManagerUnreadableJarTest {
	@Test
	fun `a jar path already reported is filtered out of a later call`() {
		val manager = IndexingServiceManager()

		val first = manager.filterNewlyUnreadableJars(listOf("a.jar", "b.jar"))
		val second = manager.filterNewlyUnreadableJars(listOf("a.jar", "c.jar"))

		assertThat(first).containsExactly("a.jar", "b.jar")
		assertThat(second).containsExactly("c.jar")
		manager.close()
	}

	@Test
	fun `close clears the reported set so a new session reports the same jar again`() {
		val manager = IndexingServiceManager()
		manager.filterNewlyUnreadableJars(listOf("a.jar"))

		manager.close()

		assertThat(manager.filterNewlyUnreadableJars(listOf("a.jar"))).containsExactly("a.jar")
	}
}
