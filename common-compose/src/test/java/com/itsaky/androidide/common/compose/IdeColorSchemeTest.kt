package com.itsaky.androidide.common.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.android.material.R as MaterialR

/**
 * The attribute-to-role mapping, tested with a fake resolver rather than a real themed
 * [android.content.Context].
 *
 * The interesting behaviour is entirely in the mapping and the per-role fallback, so making resolution
 * a parameter buys full coverage with no Robolectric and no theme fixtures.
 */
class IdeColorSchemeTest {
	private val red = Color(0xFFFF0000)
	private val green = Color(0xFF00FF00)

	/**
	 * Every role [ideColorScheme] claims to map, paired with its name for readable failures.
	 *
	 * [ColorScheme] has no structural `equals`, so whole-scheme comparison would compare identity and
	 * pass vacuously. Listing the roles also makes "did the mapping forget one?" a real assertion.
	 */
	private fun mappedRoles(scheme: ColorScheme): List<Pair<String, Color>> =
		listOf(
			"primary" to scheme.primary,
			"onPrimary" to scheme.onPrimary,
			"primaryContainer" to scheme.primaryContainer,
			"onPrimaryContainer" to scheme.onPrimaryContainer,
			"secondary" to scheme.secondary,
			"onSecondary" to scheme.onSecondary,
			"secondaryContainer" to scheme.secondaryContainer,
			"onSecondaryContainer" to scheme.onSecondaryContainer,
			"tertiary" to scheme.tertiary,
			"onTertiary" to scheme.onTertiary,
			"tertiaryContainer" to scheme.tertiaryContainer,
			"onTertiaryContainer" to scheme.onTertiaryContainer,
			"background" to scheme.background,
			"onBackground" to scheme.onBackground,
			"surface" to scheme.surface,
			"onSurface" to scheme.onSurface,
			"surfaceVariant" to scheme.surfaceVariant,
			"onSurfaceVariant" to scheme.onSurfaceVariant,
			"outline" to scheme.outline,
			"outlineVariant" to scheme.outlineVariant,
			"error" to scheme.error,
			"onError" to scheme.onError,
			"errorContainer" to scheme.errorContainer,
			"onErrorContainer" to scheme.onErrorContainer,
		)

	@Test
	fun `a resolved attribute wins over the baseline`() {
		val scheme = ideColorScheme(dark = false) { attr -> red.takeIf { attr == MaterialR.attr.colorPrimary } }

		assertEquals(red, scheme.primary)
	}

	@Test
	fun `an undefined attribute falls back to the light baseline`() {
		val scheme = ideColorScheme(dark = false) { null }

		assertEquals(mappedRoles(lightColorScheme()), mappedRoles(scheme))
	}

	@Test
	fun `an undefined attribute falls back to the dark baseline`() {
		val scheme = ideColorScheme(dark = true) { null }

		assertEquals(mappedRoles(darkColorScheme()), mappedRoles(scheme))
	}

	@Test
	fun `roles fall back individually, so a partial theme still yields sensible colours`() {
		// A theme defining only the surface pair, as a minimal overlay might.
		val scheme =
			ideColorScheme(dark = false) { attr ->
				when (attr) {
					MaterialR.attr.colorSurface -> red
					MaterialR.attr.colorOnSurface -> green
					else -> null
				}
			}

		assertEquals(red, scheme.surface)
		assertEquals(green, scheme.onSurface)
		// Everything else keeps the baseline rather than going transparent or black.
		assertEquals(lightColorScheme().primary, scheme.primary)
		assertEquals(lightColorScheme().error, scheme.error)
	}

	@Test
	fun `background reads the platform attribute, not a Material one`() {
		// colorBackground has no Material equivalent; mapping it to one would silently lose the theme's
		// window background.
		val scheme = ideColorScheme(dark = false) { attr -> red.takeIf { attr == android.R.attr.colorBackground } }

		assertEquals(red, scheme.background)
	}

	@Test
	fun `every role the mapping claims to cover is actually resolved`() {
		// Resolving everything to one colour proves no listed role was left out of the copy() call: an
		// unmapped role would still hold its baseline value.
		val scheme = ideColorScheme(dark = false) { red }

		val unmapped = mappedRoles(scheme).filter { (_, color) -> color != red }
		assertTrue("roles not read from the theme: ${unmapped.map { it.first }}", unmapped.isEmpty())
	}

	@Test
	fun `the dark baseline differs from the light one, so the flag is not ignored`() {
		val light = ideColorScheme(dark = false) { null }
		val dark = ideColorScheme(dark = true) { null }

		assertTrue(mappedRoles(light) != mappedRoles(dark))
	}
}
