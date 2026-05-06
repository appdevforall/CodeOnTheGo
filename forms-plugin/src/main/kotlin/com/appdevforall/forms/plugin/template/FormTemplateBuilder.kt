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

    @Suppress("unused")
    private fun unused(@Suppress("UNUSED_PARAMETER") f: FormField) { /* placeholder for type linkage */ }
}
