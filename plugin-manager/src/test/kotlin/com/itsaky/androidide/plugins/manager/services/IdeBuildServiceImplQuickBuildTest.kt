package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildState
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import org.junit.After
import org.junit.Test
import java.util.concurrent.CompletableFuture

class IdeBuildServiceImplQuickBuildTest {
	private val service = IdeBuildServiceImpl.getInstance()

	private class FakeQuickBuildProvider : QuickBuildProvider {
		var starts = 0
		var cancels = 0

		override fun availability() = QuickBuildAvailability.Available

		override fun start(): CompletableFuture<QuickBuildResult> {
			starts++
			return CompletableFuture.completedFuture(QuickBuildResult.UpToDate)
		}

		override fun cancel(): Boolean {
			cancels++
			return true
		}

		override fun status() = QuickBuildState.BUILDING
	}

	private class ThrowingQuickBuildProvider : QuickBuildProvider {
		override fun availability(): QuickBuildAvailability = error("availability")

		override fun start(): CompletableFuture<QuickBuildResult> = error("start")

		override fun cancel(): Boolean = error("cancel")

		override fun status(): QuickBuildState = error("status")
	}

	@After
	fun clearProvider() {
		service.setQuickBuildProvider(null)
	}

	@Test
	fun withoutAProviderQuickBuildIsNotAvailable() {
		service.setQuickBuildProvider(null)

		val availability = service.getQuickBuildAvailability()
		assertThat(availability).isInstanceOf(QuickBuildAvailability.Unavailable::class.java)
		assertThat((availability as QuickBuildAvailability.Unavailable).reason).isEqualTo(QuickBuildUnavailableReason.NOT_SUPPORTED)
		assertThat(service.startQuickBuild().get()).isInstanceOf(QuickBuildResult.Refused::class.java)
		assertThat(service.cancelQuickBuild().get()).isFalse()
		assertThat(service.getQuickBuildStatus()).isEqualTo(QuickBuildState.UNAVAILABLE)
	}

	@Test
	fun withAProviderEveryCallReachesIt() {
		val provider = FakeQuickBuildProvider()
		service.setQuickBuildProvider(provider)

		assertThat(service.getQuickBuildAvailability()).isEqualTo(QuickBuildAvailability.Available)
		assertThat(service.startQuickBuild().get()).isEqualTo(QuickBuildResult.UpToDate)
		assertThat(service.cancelQuickBuild().get()).isTrue()
		assertThat(service.getQuickBuildStatus()).isEqualTo(QuickBuildState.BUILDING)
		assertThat(provider.starts).isEqualTo(1)
		assertThat(provider.cancels).isEqualTo(1)
	}

	@Test
	fun aThrowingProviderReachesThePluginAsAResult() {
		service.setQuickBuildProvider(ThrowingQuickBuildProvider())

		assertThat(service.getQuickBuildAvailability()).isInstanceOf(QuickBuildAvailability.Unavailable::class.java)
		assertThat(service.startQuickBuild().get()).isEqualTo(QuickBuildResult.Refused("Quick Build failed to start: start"))
		assertThat(service.cancelQuickBuild().get()).isFalse()
		assertThat(service.getQuickBuildStatus()).isEqualTo(QuickBuildState.UNAVAILABLE)
	}

	@Test
	fun aRunThatFailsExceptionallyReachesThePluginAsAFailure() {
		service.setQuickBuildProvider(
			object : QuickBuildProvider by FakeQuickBuildProvider() {
				override fun start() = CompletableFuture<QuickBuildResult>().apply { completeExceptionally(IllegalStateException("boom")) }
			},
		)

		assertThat(service.startQuickBuild().get()).isEqualTo(QuickBuildResult.Failed("boom", null))
	}
}
