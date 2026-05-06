package com.appdevforall.forms.plugin

import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Smoke test: build a `.cgt` archive via [CgtTemplateBuilder] (the same
 * surface the Forms plugin uses on activate / on wizard finish) and assert
 * the archive's structure matches what the IDE's `ZipTemplateReader` +
 * `ZipRecipeExecutor` expect.
 *
 * This is the closest thing we have to an integration test for the
 * plugin → `IdeTemplateService` → New Project grid pipeline; it only
 * exercises the build half (the read half lives in `templates-impl/` and
 * needs Android resources to run). If this test breaks, the actual
 * registration pipeline almost certainly broke too, so failing fast in
 * `:test` is worth it.
 */
class CgtRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun staticStubLikeCgtHasIndexMetadataAndAssets() {
        val outDir = tempFolder.newFolder("cgt-out")

        val builder = CgtTemplateBuilder("Form-filling app from photo")
            .description("Static stub registered by the Forms plugin")
            .tooltipTag("forms_plugin.template")
            .version("1.0")
            .showLanguageOption()
            .showMinSdkOption()
            .showPackageNameOption()

        builder.addTemplateFile(
            "settings.gradle.kts",
            """rootProject.name = "{{ APP_NAME }}"""",
        )
        builder.addStaticFile(
            "app/src/main/assets/form_schema.json",
            "{\"appName\":\"FormApp\"}",
        )

        val cgt = builder.build(outDir)
        assertTrue(cgt.exists())
        assertTrue(cgt.length() > 0)
        // dirName is alphanumeric-only (DIR_NAME_REGEX strips spaces+hyphens),
        // so a templateName like "Form-filling app from photo" lands as a
        // single-token cgt filename. Pin that here so a future regex change
        // surfaces in this test rather than at registration time.
        assertEquals("Formfillingappfromphoto.cgt", cgt.name)

        ZipFile(cgt).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()

            // templates.json at the archive root drives ZipTemplateReader's
            // template discovery loop.
            assertTrue("missing templates.json", "templates.json" in entries)

            val basePath = "Formfillingappfromphoto"
            assertTrue("missing template.json under $basePath/template/",
                "$basePath/template/template.json" in entries)

            // Pebble-templated files are renamed to *.peb on the way in;
            // static files keep their original extension.
            assertTrue("missing settings.gradle.kts.peb (template-mode entry)",
                "$basePath/settings.gradle.kts.peb" in entries)
            assertTrue("missing assets file (static-mode entry)",
                "$basePath/app/src/main/assets/form_schema.json" in entries)
        }
    }

    @Test
    fun pebbleSyntaxConversionRunsOnTemplateContentNotPath() {
        // Verify the toPebbleSyntax pass: {{ APP_NAME }} must arrive in the
        // archive as ${{ APP_NAME }} so ZipRecipeExecutor's PebbleEngine sees
        // its print delimiter. Path entries are just sanitized, not rewritten.
        val outDir = tempFolder.newFolder("cgt-out")
        val builder = CgtTemplateBuilder("test")
        builder.addTemplateFile(
            "build.gradle.kts",
            """rootProject.name = "{{ APP_NAME }}"""",
        )
        val cgt = builder.build(outDir)

        ZipFile(cgt).use { zip ->
            val entry = zip.getEntry("test/build.gradle.kts.peb")
            assertNotNull("missing entry test/build.gradle.kts.peb", entry)
            val content = zip.getInputStream(entry!!).bufferedReader().use { it.readText() }
            assertTrue(
                "expected ${'$'}{{ APP_NAME }} but content was: $content",
                content.contains("\${{ APP_NAME }}"),
            )
        }
    }

    @Test
    fun absolutePathsInTemplateFilesAreRejected() {
        val outDir = tempFolder.newFolder("cgt-out")
        val builder = CgtTemplateBuilder("test")
        try {
            builder.addTemplateFile("/etc/passwd", "leak")
            // sanitizePath should have thrown before we reach build.
            assertTrue("expected IllegalArgumentException for absolute path", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun pathTraversalInTemplateFilesIsRejected() {
        val outDir = tempFolder.newFolder("cgt-out")
        val builder = CgtTemplateBuilder("test")
        try {
            builder.addTemplateFile("../../etc/passwd", "leak")
            assertTrue("expected IllegalArgumentException for path traversal", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
