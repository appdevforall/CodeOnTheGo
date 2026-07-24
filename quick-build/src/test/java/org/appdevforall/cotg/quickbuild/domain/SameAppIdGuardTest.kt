package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SameAppIdGuardTest {
	private val realAppId = "com.example.app"
	private val testAppId = "com.example.app.quickbuild"
	private val guard = SameAppIdGuard()

	@Test
	fun `suffix mode allows the suffixed test package`() {
		guard.checkInstall(sameAppId = false, targetPackage = testAppId, realApplicationId = realAppId)
	}

	@Test
	fun `suffix mode never installs over the real id`() {
		assertThrows<SameAppIdGuard.Violation> {
			guard.checkInstall(sameAppId = false, targetPackage = realAppId, realApplicationId = realAppId)
		}
	}

	@Test
	fun `suffix mode violation names both ids and the one action to take`() {
		val error =
			assertThrows<SameAppIdGuard.Violation> {
				guard.checkInstall(sameAppId = false, targetPackage = realAppId, realApplicationId = realAppId)
			}

		assertThat(error).hasMessageThat().contains(realAppId)
		assertThat(error).hasMessageThat().contains("rebaseline")
	}

	@Test
	fun `suffix mode rejects a suffixed target that equals the real id`() {
		// A confused state where the "real" id already carries the suffix: the target
		// must still DIFFER from the real id or the install aborts.
		assertThrows<SameAppIdGuard.Violation> {
			guard.checkInstall(sameAppId = false, targetPackage = testAppId, realApplicationId = testAppId)
		}
	}

	@Test
	fun `same-id mode requires this episode's token`() {
		assertThrows<SameAppIdGuard.Violation> {
			guard.checkInstall(sameAppId = true, targetPackage = realAppId, realApplicationId = realAppId)
		}

		guard.mintClobberToken(realAppId)
		guard.checkInstall(sameAppId = true, targetPackage = realAppId, realApplicationId = realAppId)
	}

	@Test
	fun `a token for another application does not authorize this one`() {
		guard.mintClobberToken("com.other.app")

		assertThrows<SameAppIdGuard.Violation> {
			guard.checkInstall(sameAppId = true, targetPackage = realAppId, realApplicationId = realAppId)
		}
	}

	@Test
	fun `same-id mode rejects a target that is not the real id`() {
		guard.mintClobberToken(realAppId)

		assertThrows<SameAppIdGuard.Violation> {
			guard.checkInstall(sameAppId = true, targetPackage = testAppId, realApplicationId = realAppId)
		}
	}

	@Test
	fun `same-id mode id-mismatch violation names both ids and the one action to take`() {
		guard.mintClobberToken(realAppId)

		val error =
			assertThrows<SameAppIdGuard.Violation> {
				guard.checkInstall(sameAppId = true, targetPackage = testAppId, realApplicationId = realAppId)
			}

		assertThat(error).hasMessageThat().contains(testAppId)
		assertThat(error).hasMessageThat().contains(realAppId)
		assertThat(error).hasMessageThat().contains("Turn same-app-id mode off and back on")
	}

	@Test
	fun `ending the episode revokes the token`() {
		guard.mintClobberToken(realAppId)
		guard.endEpisode()

		assertThat(guard.isEpisodeConfirmed()).isFalse()
		assertThrows<SameAppIdGuard.Violation> {
			guard.checkInstall(sameAppId = true, targetPackage = realAppId, realApplicationId = realAppId)
		}
	}

	@Test
	fun `uninstalling the real id requires the token`() {
		assertThrows<SameAppIdGuard.Violation> {
			guard.checkUninstall(realAppId, realAppId)
		}

		guard.mintClobberToken(realAppId)
		guard.checkUninstall(realAppId, realAppId)
	}

	@Test
	fun `uninstalling the suffixed test app never needs a token`() {
		guard.checkUninstall(testAppId, realAppId)
	}

	@Test
	fun `versionCode override may repeat or grow but never decrease`() {
		guard.mintClobberToken(realAppId)
		guard.checkVersionCodeOverride(5)
		guard.checkVersionCodeOverride(5)
		guard.checkVersionCodeOverride(6)

		assertThrows<SameAppIdGuard.Violation> { guard.checkVersionCodeOverride(5) }
	}

	@Test
	fun `the versionCode floor resets with the episode`() {
		guard.mintClobberToken(realAppId)
		guard.checkVersionCodeOverride(100)
		guard.endEpisode()

		// A new episode may legitimately pin lower (the real app was reinstalled at a
		// lower versionCode); only WITHIN an episode is a decrease a bug.
		guard.mintClobberToken(realAppId)
		guard.checkVersionCodeOverride(5)
	}

	@Test
	fun `episode confirmation is observable`() {
		assertThat(guard.isEpisodeConfirmed()).isFalse()
		guard.mintClobberToken(realAppId)
		assertThat(guard.isEpisodeConfirmed()).isTrue()
	}
}
