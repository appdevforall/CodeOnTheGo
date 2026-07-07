package com.itsaky.androidide.actions.profiler

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseVariantTest {

    @Test
    fun `maps plain debug to release`() {
        assertThat(releaseVariantName("debug", listOf("debug", "release")))
            .isEqualTo("release")
    }

    @Test
    fun `maps flavored debug to flavored release preserving flavor`() {
        assertThat(
            releaseVariantName("freeDebug", listOf("freeDebug", "freeRelease", "paidDebug", "paidRelease"))
        ).isEqualTo("freeRelease")
    }

    @Test
    fun `already-release variant passes through`() {
        assertThat(releaseVariantName("release", listOf("debug", "release")))
            .isEqualTo("release")
        assertThat(releaseVariantName("freeRelease", listOf("freeDebug", "freeRelease")))
            .isEqualTo("freeRelease")
    }

    @Test
    fun `returns null for custom build type`() {
        assertThat(releaseVariantName("staging", listOf("debug", "release", "staging")))
            .isNull()
    }

    @Test
    fun `returns null when computed release variant does not exist`() {
        // Release build type filtered out of this module.
        assertThat(releaseVariantName("freeDebug", listOf("freeDebug", "paidDebug")))
            .isNull()
    }
}
