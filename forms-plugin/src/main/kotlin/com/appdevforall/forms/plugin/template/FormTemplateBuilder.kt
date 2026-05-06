package com.appdevforall.forms.plugin.template

import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.SubmitConfig
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File

/**
 * Builds a `.cgt` archive whose generated project renders the given [FormSchema]
 * at runtime.
 *
 * For C1 the generated project is intentionally minimal — Pebble-templated
 * AndroidManifest, build.gradle.kts, settings.gradle.kts, MainActivity stub —
 * enough to compile and launch on device. The schema is shipped as a single
 * `assets/form_schema.json` static file. C5 fills in the runtime renderer,
 * validators, and submitters that read the schema.
 *
 * Static-stub mode (no fields, no submit config) is what C1 registers on plugin
 * activate(); per-instance mode is what C2's wizard re-registers after the user
 * captures a real schema.
 */
class FormTemplateBuilder(
    private val pluginContext: PluginContext,
    private val templateService: IdeTemplateService,
) {

    /**
     * Build and register a `.cgt` for the given schema. Returns the registered
     * filename (caller can use it to unregister later) or null on failure.
     *
     * The template name is decorated with [schema.appName] when present so the
     * user sees a recognisable card in the New Project grid; the static stub
     * keeps a single fixed name so re-activate doesn't pile up duplicates.
     */
    fun buildAndRegister(schema: FormSchema, isStaticStub: Boolean): String? {
        val outputDir = File(pluginContext.resources.getPluginDirectory(), "generated-cgt")
            .also { it.mkdirs() }

        val builderName = if (isStaticStub) STATIC_STUB_TEMPLATE_NAME else schema.appName
        val builder = templateService.createTemplateBuilder(builderName)

        builder
            .description(if (isStaticStub) STATIC_STUB_DESCRIPTION else describe(schema))
            .tooltipTag(TOOLTIP_TAG)
            .version(TEMPLATE_VERSION)
            .showLanguageOption()
            .showMinSdkOption()
            .showPackageNameOption()

        addProjectScaffolding(builder)
        addAppModuleScaffolding(builder, schema, isStaticStub)

        val cgt = try {
            builder.build(outputDir)
        } catch (t: Throwable) {
            pluginContext.logger.error("Failed to build forms template archive", t)
            return null
        }

        val ok = templateService.registerTemplate(cgt)
        if (!ok) {
            pluginContext.logger.error("templateService.registerTemplate returned false for ${cgt.name}")
            return null
        }
        pluginContext.logger.info("Registered forms template: ${cgt.name}")
        return cgt.name
    }

    private fun describe(schema: FormSchema): String {
        val n = schema.fields.size
        return "Form-filling app generated from photo. $n field" +
            (if (n == 1) "" else "s") + ". Captured by the Forms wizard."
    }

    private fun addProjectScaffolding(builder: CgtTemplateBuilder) {
        builder.addTemplateFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "{{ APP_NAME }}"
            include(":app")
            """.trimIndent(),
        )

        builder.addTemplateFile(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "{{ AGP_VERSION }}" apply false
                id("org.jetbrains.kotlin.android") version "{{ KOTLIN_VERSION }}" apply false
            }
            """.trimIndent(),
        )

        builder.addTemplateFile(
            "gradle.properties",
            """
            android.useAndroidX=true
            android.enableJetifier=true
            kotlin.code.style=official
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            """.trimIndent(),
        )
    }

    private fun addAppModuleScaffolding(
        builder: CgtTemplateBuilder,
        schema: FormSchema,
        isStaticStub: Boolean,
    ) {
        builder.addTemplateFile(
            "app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "{{ PACKAGE_NAME }}"
                compileSdk = {{ COMPILE_SDK }}

                defaultConfig {
                    applicationId = "{{ PACKAGE_NAME }}"
                    minSdk = {{ MIN_SDK }}
                    targetSdk = {{ TARGET_SDK }}
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_{{ JAVA_SOURCE_COMPAT }}
                    targetCompatibility = JavaVersion.VERSION_{{ JAVA_TARGET_COMPAT }}
                }
                kotlinOptions {
                    jvmTarget = "{{ JAVA_TARGET }}"
                }
            }

            dependencies {
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.10.0")
            }
            """.trimIndent(),
        )

        builder.addTemplateFile(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <application
                    android:label="{{ APP_NAME }}"
                    android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar"
                    android:allowBackup="true">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
        )

        builder.addTemplateFile(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">{{ APP_NAME }}</string>
            </resources>
            """.trimIndent(),
        )

        // C5 will replace this with a renderer that reads form_schema.json. C1's
        // stub just shows a placeholder TextView so the project compiles + runs.
        // Note: the path uses the literal token `PACKAGE_NAME` (no braces) — the
        // ZipRecipeExecutor rewrites that token in zip-entry paths to the
        // module's package as slashes (com.example.foo -> com/example/foo). The
        // file *content* uses `{{ PACKAGE_NAME }}` (Pebble syntax) which
        // renders to the dotted package.
        builder.addTemplateFile(
            "app/src/main/java/PACKAGE_NAME/MainActivity.kt",
            STUB_MAIN_ACTIVITY,
        )

        builder.addStaticFile("app/src/main/assets/form_schema.json", schema.toJson())

        if (isStaticStub) {
            // Stub-mode README to make it clear what the user is looking at.
            builder.addStaticFile(
                "README.md",
                """
                # Form-data app (blank stub)

                This is a placeholder generated by the Forms plugin's static template.
                Re-run the **Form-filling app from photo** wizard from the IDE
                sidebar to capture a real form schema; that registers a per-instance
                template you can pick from + Create Project instead.
                """.trimIndent(),
            )
        }
    }

    companion object {
        const val STATIC_STUB_TEMPLATE_NAME = "Form-filling app from photo"
        const val STATIC_STUB_DESCRIPTION =
            "Blank starter for an offline-capable form-data app. Use the Forms wizard " +
                "from the IDE sidebar to scaffold a richer schema."
        const val TOOLTIP_TAG = "forms_plugin.template"
        const val TEMPLATE_VERSION = "1.0"

        /** The blank-stub schema registered on plugin activation. */
        fun blankStubSchema(): FormSchema = FormSchema(
            appName = STATIC_STUB_TEMPLATE_NAME,
            packageName = "com.example.formapp",
            fields = emptyList(),
            submit = SubmitConfig(),
        )

        /**
         * Stub MainActivity. Kept tiny so the generated project compiles cleanly
         * on the reference 4 GB device without Material 3 / Compose deps.
         */
        private val STUB_MAIN_ACTIVITY = """
            package {{ PACKAGE_NAME }}

            import android.os.Bundle
            import android.widget.TextView
            import androidx.appcompat.app.AppCompatActivity

            /**
             * Placeholder generated by the Forms plugin's static template. C5 will
             * replace this with a runtime form renderer that reads
             * `assets/form_schema.json`.
             */
            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val tv = TextView(this)
                    tv.text = "Form app stub. Re-run the Forms wizard to capture fields."
                    setContentView(tv)
                }
            }
        """.trimIndent()
    }

    @Suppress("unused")
    private fun unused(@Suppress("UNUSED_PARAMETER") f: FormField) { /* placeholder for type linkage */ }
}
