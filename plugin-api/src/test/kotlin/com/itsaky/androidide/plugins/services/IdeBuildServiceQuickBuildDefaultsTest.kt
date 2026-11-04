package com.itsaky.androidide.plugins.services

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A host that does not implement Quick Build reports it unavailable rather than failing. */
class IdeBuildServiceQuickBuildDefaultsTest {
	private val service =
		object : IdeBuildService {
			override fun isBuildInProgress() = false

			override fun isToolingServerStarted() = true

			override fun addBuildStatusListener(callback: BuildStatusListener) = Unit

			override fun removeBuildStatusListener(callback: BuildStatusListener) = Unit
		}

	@Test
	fun availabilityDefaultsToNotSupported() {
		val availability = service.getQuickBuildAvailability()

		assertThat(availability).isInstanceOf(QuickBuildAvailability.Unavailable::class.java)
		assertThat((availability as QuickBuildAvailability.Unavailable).reason).isEqualTo(QuickBuildUnavailableReason.NOT_SUPPORTED)
	}

	@Test
	fun startDefaultsToRefusedWithoutBuilding() {
		val result = service.startQuickBuild()

		assertThat(result.isDone).isTrue()
		assertThat(result.get()).isInstanceOf(QuickBuildResult.Refused::class.java)
	}

	@Test
	fun cancelDefaultsToNothingCancelled() {
		assertThat(service.cancelQuickBuild().get()).isFalse()
	}

	@Test
	fun statusDefaultsToUnavailable() {
		assertThat(service.getQuickBuildStatus()).isEqualTo(QuickBuildState.UNAVAILABLE)
	}
}
