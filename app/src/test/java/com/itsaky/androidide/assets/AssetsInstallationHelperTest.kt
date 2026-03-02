package com.itsaky.androidide.assets

import android.content.Context
import com.itsaky.androidide.assets.AssetsInstallationHelper.Result.Failure
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException

class AssetsInstallationHelperTest {

    private val ctx: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkObject(AssetsInstallationHelper)
    }

    @Test
    fun `install with missing asset skips sentry`() = runBlocking {
        val helper = AssetsInstallationHelper

        every {
            helper["checkStorageAccessibility"](any<Context>(), any<AssetsInstallerProgressConsumer>())
        } returns null

        coEvery {
            helper["doInstall"](any<Context>(), any<AssetsInstallerProgressConsumer>())
        } throws FileNotFoundException("data/common/gradle.zip.br")

        val result = helper.install(ctx)

        assertTrue("Expected Result.Failure", result is Failure)
        val failure = result as Failure
        assertFalse("Should skip Sentry report", failure.shouldReportToSentry)
        assertTrue(
            "Expected MissingAssetsEntryException as cause",
            failure.cause is MissingAssetsEntryException
        )
        assertTrue(
            "Expected FileNotFoundException as root cause",
            (failure.cause?.cause) is FileNotFoundException
        )
    }
}
