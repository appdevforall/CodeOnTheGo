package com.appdevforall.forms.plugin.template

import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.SubmitConfig
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File

/**
 * Builds a single static `.cgt` archive that scaffolds a generic
 * form-fillable Android app with a stub `assets/form_schema.json`. Pebble
 * templates `AndroidManifest.xml` / `build.gradle.kts` / `MainActivity.kt`
 * give the user a project that compiles and runs out of the box; the
 * actual schema lives in `assets/form_schema.json` and is rewritten by
 * the Forms wizard after the user opens the project.
 *
 * The class historically also built a per-wizard-run `.cgt` carrying a
 * captured schema, but the new architecture writes the captured schema
 * directly into the open project's assets dir instead. The per-run path
 * is gone; this builder now only ever emits the static stub.
 */
class FormTemplateBuilder(
    private val pluginContext: PluginContext,
    private val templateService: IdeTemplateService,
) {

    /**
     * Build and register the static "Form-filling app from photo" stub.
     * Returns the registered filename on success or null on failure.
     *
     * Idempotent: calling this on every plugin activation overwrites the
     * previous stub registration with byte-identical content.
     */
    fun buildAndRegisterStaticStub(): String? {
        val outputDir = File(pluginContext.resources.getPluginDirectory(), "generated-cgt")
            .also { it.mkdirs() }

        val builder = templateService.createTemplateBuilder(STATIC_STUB_TEMPLATE_NAME)

        builder
            .description(STATIC_STUB_DESCRIPTION)
            .tooltipTag(TOOLTIP_TAG)
            .version(TEMPLATE_VERSION)
            .showLanguageOption()
            .showMinSdkOption()
            .showPackageNameOption()

        addProjectScaffolding(builder)
        addAppModuleScaffolding(builder, blankStubSchema())

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
                implementation("androidx.core:core-ktx:1.12.0")
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

        builder.addStaticFile(
            "README.md",
            """
            # Form-data app

            This project was scaffolded by the Forms plugin's static
            "Form-filling app from photo" template. Out of the box it ships a
            stub schema with one placeholder field — open the project, then
            tap **📷 Capture form from photo** in the IDE side menu (or the
            editor toolbar) to launch the capture wizard. The wizard writes a
            new `app/src/main/assets/form_schema.json` and the app picks it
            up on next rebuild.
            """.trimIndent(),
        )
    }

    companion object {
        // The user picks this card from the New Project grid. It scaffolds a
        // form-app shell with a one-field placeholder schema; the real schema
        // is then captured by the wizard and written into the open project's
        // assets dir.
        const val STATIC_STUB_TEMPLATE_NAME = "Form-filling app from photo"
        const val STATIC_STUB_DESCRIPTION =
            "Generate an offline-capable Android app for filling a paper form. " +
                "Open the project, then use the Forms side menu to capture fields " +
                "from a photo of the form."
        const val TOOLTIP_TAG = "forms_plugin.template"
        const val TEMPLATE_VERSION = "1.0"

        /**
         * The schema written into the static stub `.cgt`. One placeholder
         * field so the generated app shows a non-empty form on first run —
         * the user knows the renderer works before they ever launch the
         * wizard. The wizard overwrites this file with real fields.
         */
        fun blankStubSchema(): FormSchema = FormSchema(
            appName = "FormApp",
            packageName = "com.example.formapp",
            fields = listOf(
                FormField(
                    id = "f_placeholder",
                    label = "Placeholder field",
                    type = com.appdevforall.forms.plugin.FieldType.TEXT,
                ),
            ),
            submit = SubmitConfig(),
        )

        /**
         * Generated app's MainActivity — reads `assets/form_schema.json` and
         * renders one `TextInputLayout` per text/long-text/number/date field
         * plus a `CheckBox` per checkbox field. On Save, validates
         * required-ness and dumps the collected record to logcat
         * (`adb logcat | grep FormApp`).
         *
         * **What's intentionally minimal in this commit:**
         * - No submitters yet — the data goes to logcat only. C5 will add
         *   HttpPostSubmitter / CsvFileSubmitter / JsonFileSubmitter and the
         *   offline queue, all driven by the `submit` block of the schema.
         * - No date picker — date fields just get a text input with an
         *   "yyyy-mm-dd" hint. Date pickers add a non-trivial amount of
         *   layout glue and aren't on the must-ship path.
         * - No `reusable` handling — that's a no-op until C5 wires up the
         *   record persistence layer.
         *
         * Kept dependency-light intentionally: appcompat + material only,
         * no Room / Compose / kotlinx.coroutines / kotlinx.serialization.
         * That keeps the generated APK under ~1 MB on the reference device.
         */
        private val STUB_MAIN_ACTIVITY = """
            package {{ PACKAGE_NAME }}

            import android.os.Bundle
            import android.text.InputType
            import android.util.Log
            import android.view.Gravity
            import android.view.View
            import android.widget.CheckBox
            import android.widget.LinearLayout
            import android.widget.ScrollView
            import android.widget.TextView
            import android.widget.Toast
            import androidx.appcompat.app.AppCompatActivity
            import com.google.android.material.button.MaterialButton
            import com.google.android.material.textfield.TextInputEditText
            import com.google.android.material.textfield.TextInputLayout
            import org.json.JSONArray
            import org.json.JSONObject
            import java.io.BufferedReader
            import java.io.InputStreamReader

            /**
             * Generated by the Forms plugin. Reads form_schema.json from assets
             * and renders the form dynamically so re-running the wizard with a
             * new schema doesn't require regenerating Kotlin sources.
             */
            class MainActivity : AppCompatActivity() {
                private data class FieldBinding(
                    val id: String,
                    val label: String,
                    val type: String,
                    val required: Boolean,
                    val edit: TextInputEditText? = null,
                    val editLayout: TextInputLayout? = null,
                    val check: CheckBox? = null,
                )

                private val bindings = mutableListOf<FieldBinding>()

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val schema = loadSchema() ?: run {
                        showError("form_schema.json not found in assets — re-run the Forms wizard.")
                        return
                    }

                    val container = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                    }
                    val scroll = ScrollView(this)
                    scroll.addView(container)

                    val title = TextView(this).apply {
                        text = schema.optString("appName", getString(R.string.app_name))
                        textSize = 22f
                    }
                    container.addView(title)

                    val fields = schema.optJSONArray("fields") ?: JSONArray()
                    if (fields.length() == 0) {
                        val empty = TextView(this).apply {
                            text = "This template was generated without any fields. " +
                                "Re-run the Forms wizard from the IDE sidebar to add some."
                            setPadding(0, dp(12), 0, dp(12))
                        }
                        container.addView(empty)
                    }

                    for (i in 0 until fields.length()) {
                        val f = fields.getJSONObject(i)
                        val id = f.optString("id", "f_" + i)
                        val label = f.optString("label", "(unnamed)")
                        val type = f.optString("type", "text")
                        val required = f.optBoolean("required", false)
                        val labelText = if (required) "${'$'}label *" else label

                        if (type == "checkbox") {
                            val cb = CheckBox(this).apply { text = labelText }
                            container.addView(cb, params())
                            bindings += FieldBinding(id, label, type, required, check = cb)
                        } else {
                            val til = TextInputLayout(this).apply { hint = labelText }
                            val edit = TextInputEditText(til.context)
                            edit.inputType = inputTypeFor(type)
                            til.addView(edit)
                            container.addView(til, params())
                            bindings += FieldBinding(id, label, type, required, edit = edit, editLayout = til)
                        }
                    }

                    val save = MaterialButton(this).apply {
                        text = "Save"
                        setOnClickListener { onSave() }
                    }
                    container.addView(save, params())
                    setContentView(scroll)
                }

                private fun onSave() {
                    var ok = true
                    val record = JSONObject()
                    for (b in bindings) {
                        val value: Any? = when (b.type) {
                            "checkbox" -> b.check?.isChecked
                            else -> {
                                val t = b.edit?.text?.toString().orEmpty().trim()
                                if (b.required && t.isEmpty()) {
                                    b.editLayout?.error = "Required"
                                    ok = false
                                    null
                                } else {
                                    b.editLayout?.error = null
                                    t
                                }
                            }
                        }
                        if (value != null) record.put(b.id, value)
                    }
                    if (!ok) {
                        Toast.makeText(this, "Fill in required fields", Toast.LENGTH_SHORT).show()
                        return
                    }
                    Log.i(TAG, "Form saved: ${'$'}{record}")
                    Toast.makeText(this, "Saved (logged to logcat)", Toast.LENGTH_SHORT).show()
                }

                private fun loadSchema(): JSONObject? {
                    return try {
                        assets.open("form_schema.json").use { stream ->
                            val text = BufferedReader(InputStreamReader(stream)).readText()
                            JSONObject(text)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to read form_schema.json", t)
                        null
                    }
                }

                private fun inputTypeFor(type: String): Int = when (type) {
                    "longtext" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    "number" -> InputType.TYPE_CLASS_NUMBER
                    "date" -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                    else -> InputType.TYPE_CLASS_TEXT
                }

                private fun showError(msg: String) {
                    val tv = TextView(this).apply {
                        text = msg
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        gravity = Gravity.CENTER
                    }
                    setContentView(tv)
                }

                private fun params(): LinearLayout.LayoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(8)
                    }

                private fun dp(value: Int): Int =
                    (value * resources.displayMetrics.density).toInt()

                companion object {
                    private const val TAG = "FormApp"
                }
            }
        """.trimIndent()
    }

}
