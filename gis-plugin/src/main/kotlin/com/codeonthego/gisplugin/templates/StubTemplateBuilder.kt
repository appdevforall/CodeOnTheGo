package com.codeonthego.gisplugin.templates

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeTemplateService
import java.io.File

/**
 * C1 stub templates.
 *
 * Both templates emit an essentially empty Android project (an `EmptyActivity`-
 * style scaffold) so we can prove end-to-end:
 *  - plugin module builds and loads
 *  - `IdeTemplateService.registerTemplate(cgtFile)` accepts our archives
 *  - both new cards appear in the New Project grid
 *  - tapping either card proceeds through `TemplateDetailsFragment` and runs
 *    the recipe to completion (project opens in the IDE)
 *
 * What we deliberately *don't* do here:
 *  - launch the wizard from the recipe — the current `IdeTemplateService` API
 *    does not let plugins inject Kotlin into the recipe (see `QUESTIONS.md`,
 *    Q1). The wizard is launchable separately (sidebar item) so the UX of the
 *    later commits can still be exercised.
 *  - emit MapLibre, GPS, POI, or annotation code. That arrives in C4–C7.
 *
 * The shared scaffold here is small enough that a single helper produces both
 * templates with only a top-line difference (template name + description). The
 * generated project compiles offline against the same Material 3 + AppCompat
 * stack that the IDE's built-in `EmptyActivity` template uses.
 */
internal object StubTemplateBuilder {

    /** Programmatic identifier used in CGT directory names. Must be filesystem-safe. */
    const val READONLY_TEMPLATE_NAME = "OSM Map - read-only POIs"
    const val ANNOTATE_TEMPLATE_NAME = "OSM Map - annotate"

    /** Tooltip tag references; the IDE composes `plugin_<id>.<tag>` if no entry matches. */
    private const val READONLY_TOOLTIP_TAG = "gis.template.readonly"
    private const val ANNOTATE_TOOLTIP_TAG = "gis.template.annotate"

    /**
     * Build both .cgt archives into [outputDir] and register them via the
     * supplied [templateService]. Returns the count of successful registrations.
     *
     * Idempotent: building the same template twice overwrites the previous
     * .cgt; `registerTemplate` likewise overwrites in the IDE's templates dir.
     */
    fun buildAndRegister(
        ctx: PluginContext,
        templateService: IdeTemplateService,
        outputDir: File
    ): Int {
        outputDir.mkdirs()

        val readonly = templateService.createTemplateBuilder(READONLY_TEMPLATE_NAME)
            .description(
                "Offline OpenStreetMap. Reads location, shows nearby places. " +
                    "C1 stub — wizard + map come in later commits."
            )
            .tooltipTag(READONLY_TOOLTIP_TAG)
            .version("0.1.0")
            .showLanguageOption()
            .showMinSdkOption()
            .let { populateScaffold(it, ctx) }
            .build(outputDir)

        val annotate = templateService.createTemplateBuilder(ANNOTATE_TEMPLATE_NAME)
            .description(
                "Offline OpenStreetMap. Drop pins with photos and metadata. " +
                    "C1 stub — wizard + camera + Room come in later commits."
            )
            .tooltipTag(ANNOTATE_TOOLTIP_TAG)
            .version("0.1.0")
            .showLanguageOption()
            .showMinSdkOption()
            .let { populateScaffold(it, ctx) }
            .build(outputDir)

        var registered = 0
        if (templateService.registerTemplate(readonly)) registered++
        if (templateService.registerTemplate(annotate)) registered++
        return registered
    }

    /**
     * Add the shared empty-project scaffolding to either CGT builder. The
     * content matches the structure produced by the in-tree `EmptyActivity`
     * template (see `assets/core.cgt -> EmptyActivity/`), trimmed to the
     * minimum that compiles. All Pebble identifiers come from
     * `ZipTemplateConstants.kt`.
     *
     * The builder receiver is returned so callers can chain `.build(outputDir)`
     * directly without intermediate locals.
     */
    private fun populateScaffold(
        builder: com.itsaky.androidide.plugins.templates.CgtTemplateBuilder,
        @Suppress("UNUSED_PARAMETER") ctx: PluginContext
    ): com.itsaky.androidide.plugins.templates.CgtTemplateBuilder {

        // settings.gradle.kts has `rootProject.name = APP_NAME`, so it needs Pebble.
        // The `.peb` suffix is added automatically by `addTemplateFile`.
        builder.addTemplateFile("settings.gradle.kts", settingsGradleKts())

        // Root build.gradle.kts is constant-content but Pebble-safe — declared as
        // a template so the file lands as `build.gradle.kts` (without .peb).
        builder.addTemplateFile("build.gradle.kts", rootBuildGradleKts())

        // gradle.properties is fully static.
        builder.addStaticFile("gradle.properties", gradleProperties())

        // The :app module's build.gradle.kts is templated for AGP / SDK substitutions.
        builder.addTemplateFile("app/build.gradle.kts", appBuildGradleKts())

        // AndroidManifest, themes, strings.
        builder.addTemplateFile(
            "app/src/main/AndroidManifest.xml",
            androidManifest()
        )
        builder.addTemplateFile(
            "app/src/main/res/values/strings.xml",
            stringsXml()
        )
        builder.addStaticFile(
            "app/src/main/res/values/themes.xml",
            themesXml()
        )
        builder.addStaticFile(
            "app/src/main/res/values/colors.xml",
            colorsXml()
        )
        builder.addStaticFile(
            "app/src/main/res/layout/activity_main.xml",
            activityMainLayout()
        )

        // Source files for both languages — the Pebble engine skips the wrong
        // language at expansion time per `ZipRecipeExecutor.shouldSkipFile`.
        builder.addTemplateFile(
            "app/src/main/java/PACKAGE_NAME/MainActivity.kt",
            mainActivityKt()
        )
        builder.addTemplateFile(
            "app/src/main/java/PACKAGE_NAME/MainActivity.java",
            mainActivityJava()
        )

        return builder
    }

    // ------------------------------------------------------------------
    //  Scaffold contents — pure strings, no resource lookups, so they're
    //  trivially testable and don't drag in Android dependencies at build
    //  time. Kept in code rather than `assets/` so a single .kt edit
    //  refreshes both CGTs.
    // ------------------------------------------------------------------

    private fun settingsGradleKts(): String = """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                google()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "{{APP_NAME}}"
        include(":app")
    """.trimIndent()

    private fun rootBuildGradleKts(): String = """
        // Top-level build file. Plugin versions live in the :app module.
        tasks.register("clean", Delete::class) {
            delete(rootProject.buildDir)
        }
    """.trimIndent()

    private fun gradleProperties(): String = """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        android.useAndroidX=true
        android.enableJetifier=false
        kotlin.code.style=official
    """.trimIndent()

    private fun appBuildGradleKts(): String = """
        plugins {
            id("com.android.application") version "{{AGP_VERSION}}"
        {% if LANGUAGE == 'kotlin' %}
            kotlin("android") version "{{KOTLIN_VERSION}}"
        {% endif %}
        }

        android {
            namespace = "{{PACKAGE_NAME}}"
            compileSdk = {{COMPILE_SDK}}

            defaultConfig {
                applicationId = "{{PACKAGE_NAME}}"
                minSdk = {{MIN_SDK}}
                targetSdk = {{TARGET_SDK}}
                versionCode = 1
                versionName = "1.0"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.{{JAVA_SOURCE_COMPAT}}
                targetCompatibility = JavaVersion.{{JAVA_TARGET_COMPAT}}
            }
        {% if LANGUAGE == 'kotlin' %}
            kotlinOptions {
                jvmTarget = "{{JAVA_TARGET}}"
            }
        {% endif %}
        }

        dependencies {
            implementation("androidx.appcompat:appcompat:1.6.1")
            implementation("com.google.android.material:material:1.10.0")
        }
    """.trimIndent()

    private fun androidManifest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            <application
                android:label="{{APP_NAME}}"
                android:theme="@style/Theme.App">

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
    """.trimIndent()

    private fun stringsXml(): String = """
        <resources>
            <string name="app_name">{{APP_NAME}}</string>
        </resources>
    """.trimIndent()

    private fun themesXml(): String = """
        <resources>
            <style name="Theme.App" parent="Theme.Material3.DayNight.NoActionBar" />
        </resources>
    """.trimIndent()

    private fun colorsXml(): String = """
        <resources>
            <color name="black">#000000</color>
            <color name="white">#FFFFFF</color>
        </resources>
    """.trimIndent()

    private fun activityMainLayout(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical"
            android:padding="24dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/app_name"
                android:textAppearance="?attr/textAppearanceHeadlineSmall" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:gravity="center"
                android:text="OSM map placeholder. Map view arrives in C4."
                android:textAppearance="?attr/textAppearanceBodyMedium" />

        </LinearLayout>
    """.trimIndent()

    private fun mainActivityKt(): String = """
        package {{PACKAGE_NAME}}

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)
            }
        }
    """.trimIndent()

    private fun mainActivityJava(): String = """
        package {{PACKAGE_NAME}};

        import android.os.Bundle;
        import androidx.appcompat.app.AppCompatActivity;

        public class MainActivity extends AppCompatActivity {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                setContentView(R.layout.activity_main);
            }
        }
    """.trimIndent()
}
