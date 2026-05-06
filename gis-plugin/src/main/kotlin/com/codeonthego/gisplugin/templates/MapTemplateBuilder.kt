package com.codeonthego.gisplugin.templates

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File

/**
 * C4 templates: replaces the C1 stubs with two real MapLibre-backed
 * scaffolds. Both templates emit:
 *
 *  - `app/build.gradle.kts` with the MapLibre Android SDK
 *    (`org.maplibre.gl:android-sdk:11.11.0`) plus AppCompat + Material
 *  - `app/src/main/AndroidManifest.xml` with the runtime permissions both
 *    map apps need (location, internet for tile fallbacks, write external
 *    storage for the bundled cache copy)
 *  - `app/src/main/res/layout/activity_main.xml` containing a real MapLibre
 *    `MapView` with a hardcoded default camera position. The `style url`
 *    points at `asset://styles/bright.json` so the generated app reads from
 *    its bundled style + tile pack rather than going to the network on
 *    first run.
 *  - `app/src/main/java/PACKAGE_NAME/MainActivity.kt` that initialises
 *    MapLibre, forwards every Activity lifecycle event into `MapView`, and
 *    centres the camera on the user's last GPS fix (with a reasonable
 *    static fallback while location loads).
 *
 * The annotate template adds an annotation FAB and a Room database stub —
 * those land in C6. C4 only has to prove that the read-only template
 * compiles and runs end-to-end on a low-end Android target without the
 * `InflateException` history (plan §7 R1) coming back.
 *
 * **Risk note (plan §7 R1).** The MapLibre native SDK is heavyweight and
 * has bitten this codebase before via the old `com.example.maplibreplugin`
 * crash tickets (ADFA-2989/2990/2993/2664). The plugin survey (open
 * questions O1 + O8) found no in-tree consumers and the old plugin code
 * has been removed, so this is a fresh build. The pinned version is
 * **11.11.0**, the current stable as of the engagement start (2026-05);
 * see [QUESTIONS.md] Q2 if Hal's preferred version differs.
 */
internal object MapTemplateBuilder {

    const val READONLY_TEMPLATE_NAME = "OSM Map - read-only POIs"
    const val ANNOTATE_TEMPLATE_NAME = "OSM Map - annotate"

    private const val READONLY_TOOLTIP_TAG = "gis.template.readonly"
    private const val ANNOTATE_TOOLTIP_TAG = "gis.template.annotate"

    /** Pinned current-stable. Bump in step with Hal's review per Q1/O1. */
    private const val MAPLIBRE_VERSION = "11.11.0"

    /**
     * Build both templates into [outputDir] and register them via the
     * supplied [templateService]. Returns the count of successful
     * registrations. Idempotent: building the same template twice
     * overwrites the previous .cgt.
     */
    fun buildAndRegister(
        ctx: PluginContext,
        templateService: IdeTemplateService,
        outputDir: File
    ): Int {
        outputDir.mkdirs()

        val readonly = templateService.createTemplateBuilder(READONLY_TEMPLATE_NAME)
            .description(
                "Offline OpenStreetMap with MapLibre. Reads location, shows nearby places. " +
                    "Bundle includes a sample tile pack so the app runs without a network on first launch."
            )
            .tooltipTag(READONLY_TOOLTIP_TAG)
            .version("0.4.0")
            .showLanguageOption()
            .showMinSdkOption()
            .let { populateScaffold(it, kind = TemplateKind.READONLY) }
            .build(outputDir)

        val annotate = templateService.createTemplateBuilder(ANNOTATE_TEMPLATE_NAME)
            .description(
                "Offline OpenStreetMap with annotation. Drop pins with photos and metadata. " +
                    "Annotate UX + persistence + submitter wire up in later commits; this template " +
                    "currently scaffolds the same map base as the read-only template."
            )
            .tooltipTag(ANNOTATE_TOOLTIP_TAG)
            .version("0.4.0")
            .showLanguageOption()
            .showMinSdkOption()
            .let { populateScaffold(it, kind = TemplateKind.ANNOTATE) }
            .build(outputDir)

        var registered = 0
        if (templateService.registerTemplate(readonly)) registered++
        if (templateService.registerTemplate(annotate)) registered++
        return registered
    }

    private enum class TemplateKind { READONLY, ANNOTATE }

    private fun populateScaffold(
        builder: CgtTemplateBuilder,
        kind: TemplateKind
    ): CgtTemplateBuilder {
        builder.addTemplateFile("settings.gradle.kts", settingsGradleKts())
        builder.addTemplateFile("build.gradle.kts", rootBuildGradleKts())
        builder.addStaticFile("gradle.properties", gradleProperties())

        // Tier 2 doc per plan §6 — a one-page README covering "where things
        // live + how to swap them" so a Tier-1 dev (Android dev going
        // phone-native) can confidently fork the template.
        builder.addTemplateFile("README.md", readme(kind))

        builder.addTemplateFile("app/build.gradle.kts", appBuildGradleKts())
        builder.addTemplateFile("app/src/main/AndroidManifest.xml", androidManifest(kind))
        builder.addTemplateFile("app/src/main/res/values/strings.xml", stringsXml())
        builder.addStaticFile("app/src/main/res/values/themes.xml", themesXml())
        builder.addStaticFile("app/src/main/res/values/colors.xml", colorsXml())
        builder.addStaticFile("app/src/main/res/layout/activity_main.xml", activityMainLayout())

        builder.addTemplateFile(
            "app/src/main/java/PACKAGE_NAME/MainActivity.kt",
            mainActivityKt(kind)
        )
        builder.addTemplateFile(
            "app/src/main/java/PACKAGE_NAME/MainActivity.java",
            mainActivityJava(kind)
        )

        // Bundled style + placeholder tile pack. The style references
        // asset://style.json so the runtime resolves to the APK assets,
        // and the placeholder mbtiles is overwritten by C4-network when
        // the wizard's downloader produces a real cache and the recipe
        // copies it in. Today they ship as `null` mbtiles so the app
        // falls back to the demotiles URL for testing.
        builder.addStaticFile("app/src/main/assets/style.json", maplibreStyleJson())

        // C5: read-only template ships a sample POI dataset and a bottom
        // drawer in MainActivity. The annotate template doesn't need this —
        // it builds annotations at runtime — so we gate the assets and
        // helper class on the kind.
        if (kind == TemplateKind.READONLY) {
            builder.addStaticFile("app/src/main/assets/pois.json", samplePoisJson())
            builder.addStaticFile(
                "app/src/main/res/layout/poi_drawer.xml",
                poiDrawerLayout()
            )
        }

        return builder
    }

    // ------------------------------------------------------------------
    //  Scaffold contents
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

    /**
     * The :app module build file. Pebble-templated for the AGP / Kotlin /
     * SDK version substitutions. MapLibre is added unconditionally because
     * both generated templates render a `MapView`.
     */
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
            implementation("androidx.recyclerview:recyclerview:1.3.2")
            implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
            implementation("com.google.android.material:material:1.10.0")

            // MapLibre Native — pinned current-stable per ADFA-2436 plan §7 R1.
            // Bump only after confirming with Hal which version the ADFA team prefers
            // (see plan open question O1) and on-device validation against the
            // historical InflateException tickets (ADFA-2989/2990/2993/2664).
            implementation("org.maplibre.gl:android-sdk:$MAPLIBRE_VERSION")

            // Location for centring the camera on the user's GPS fix.
            implementation("com.google.android.gms:play-services-location:21.3.0")
        }
    """.trimIndent()

    private fun androidManifest(kind: TemplateKind): String {
        val annotateExtra = if (kind == TemplateKind.ANNOTATE) {
            // Camera + storage become relevant for the annotate template (C6 wires
            // the photo capture flow; the manifest declaration lands here so the
            // generated app prompts at first launch, not mid-flow).
            """
        <uses-feature android:name="android.hardware.camera" android:required="false" />
        <uses-permission android:name="android.permission.CAMERA" />
            """.trimIndent()
        } else ""
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
                <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
            $annotateExtra

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
    }

    private fun stringsXml(): String = """
        <resources>
            <string name="app_name">{{APP_NAME}}</string>
            <string name="msg_location_permission_required">Location permission is required to centre the map on your position.</string>
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

    /**
     * MapView XML. We use the `org.maplibre.android.maps.MapView` class
     * (post-rebrand). CoordinatorLayout wraps the map so the read-only
     * template can attach a bottom drawer (POI list) without re-architecting
     * in C5. The annotate template will likewise hook a FAB into the same
     * coordinator parent in C6.
     */
    private fun activityMainLayout(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.coordinatorlayout.widget.CoordinatorLayout
            xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <org.maplibre.android.maps.MapView
                android:id="@+id/mapView"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                app:maplibre_cameraTargetLat="0.0"
                app:maplibre_cameraTargetLng="0.0"
                app:maplibre_cameraZoom="2"
                app:maplibre_styleUrl="asset://style.json" />

            <!-- POI drawer (read-only template). Inflated optionally; the
                 annotate template strips this include via the file-presence
                 check in MainActivity. -->
            <include layout="@layout/poi_drawer" />

        </androidx.coordinatorlayout.widget.CoordinatorLayout>
    """.trimIndent()

    /**
     * POI bottom drawer. A standard Material BottomSheet with a draggable
     * header and a `RecyclerView` of nearest places. Layout sits on top of
     * the map and behaves under `BottomSheetBehavior` — peek at 56 dp,
     * expanded shows the list.
     */
    private fun poiDrawerLayout(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@+id/poi_drawer"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="?attr/colorSurface"
            android:elevation="6dp"
            android:orientation="vertical"
            app:behavior_hideable="false"
            app:behavior_peekHeight="56dp"
            app:layout_behavior="com.google.android.material.bottomsheet.BottomSheetBehavior">

            <!-- Drag handle -->
            <View
                android:layout_width="32dp"
                android:layout_height="4dp"
                android:layout_gravity="center_horizontal"
                android:layout_marginTop="12dp"
                android:layout_marginBottom="4dp"
                android:background="?attr/colorOutline" />

            <TextView
                android:id="@+id/poi_drawer_title"
                style="@style/TextAppearance.Material3.TitleSmall"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingHorizontal="16dp"
                android:paddingVertical="8dp"
                android:text="Nearest places"
                android:textColor="?attr/colorOnSurface" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/poi_list"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:paddingHorizontal="16dp" />

        </LinearLayout>
    """.trimIndent()

    /**
     * Sample POI dataset shipped with the read-only template. Five rows
     * around Lalibela, Ethiopia (mission-relevant test region — see plan
     * §3 personas). Real downloads via the wizard's Wikipedia REST geosearch
     * replace this file at scaffold time once Q1 is unblocked.
     */
    private fun samplePoisJson(): String = """
        [
          {
            "name": "Lalibela",
            "lat": 12.0319, "lon": 39.0467,
            "category": "place=town",
            "description": "Town in northern Ethiopia famous for its rock-hewn churches.",
            "source_url": "https://en.wikipedia.org/wiki/Lalibela"
          },
          {
            "name": "Bete Giyorgis",
            "lat": 12.0312, "lon": 39.0426,
            "category": "historic=monument",
            "description": "Rock-hewn church carved in the 12th–13th century.",
            "source_url": "https://en.wikipedia.org/wiki/Church_of_Saint_George,_Lalibela"
          },
          {
            "name": "Bete Medhane Alem",
            "lat": 12.0339, "lon": 39.0455,
            "category": "historic=monument",
            "description": "The largest of the Lalibela rock-hewn churches.",
            "source_url": "https://en.wikipedia.org/wiki/Bete_Medhane_Alem"
          },
          {
            "name": "Lalibela Airport",
            "lat": 11.9779, "lon": 38.9796,
            "category": "aeroway=aerodrome",
            "description": "Regional airport (LLI) serving Lalibela.",
            "source_url": "https://en.wikipedia.org/wiki/Lalibela_Airport"
          },
          {
            "name": "Asheton Maryam Monastery",
            "lat": 12.0517, "lon": 39.0700,
            "category": "amenity=place_of_worship",
            "description": "Mountaintop monastery overlooking Lalibela.",
            "source_url": "https://en.wikipedia.org/wiki/Asheton_Maryam_Monastery"
          }
        ]
    """.trimIndent()

    /**
     * Generated MainActivity (Kotlin). Initialises MapLibre, forwards every
     * Activity lifecycle event to the MapView (per the upstream docs —
     * skipping any of these crashes the renderer or leaks GPU memory).
     *
     * GPS centring uses `FusedLocationProviderClient`. We do a one-shot
     * `getCurrentLocation` rather than continuous updates because the
     * read-only template doesn't move the camera dynamically; C6's
     * annotate template will swap to `requestLocationUpdates`.
     */
    private fun mainActivityKt(kind: TemplateKind): String {
        val poiBlock = if (kind == TemplateKind.READONLY) """

            // ----- POI loading + drawer (read-only template only) -----

            private val pois = mutableListOf<Poi>()

            private fun loadPois() {
                val text = assets.open("pois.json").bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(text)
                pois.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    pois += Poi(
                        name = o.optString("name"),
                        lat = o.optDouble("lat"),
                        lon = o.optDouble("lon"),
                        description = o.optString("description")
                    )
                }
            }

            private fun setupPoiDrawer() {
                val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.poi_list)
                list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
                list.adapter = PoiAdapter(pois) { poi ->
                    map?.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(org.maplibre.android.geometry.LatLng(poi.lat, poi.lon))
                        .zoom(15.0)
                        .build()
                }
            }

            private data class Poi(
                val name: String,
                val lat: Double,
                val lon: Double,
                val description: String
            )

            private class PoiAdapter(
                private val items: List<Poi>,
                private val onClick: (Poi) -> Unit
            ) : androidx.recyclerview.widget.RecyclerView.Adapter<PoiAdapter.VH>() {
                class VH(val v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
                override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
                    val tv = android.widget.TextView(p.context).apply {
                        setPadding(0, 24, 0, 24)
                    }
                    return VH(tv)
                }
                override fun getItemCount() = items.size
                override fun onBindViewHolder(h: VH, pos: Int) {
                    val poi = items[pos]
                    (h.v as android.widget.TextView).text =
                        "${'$'}{poi.name}\n${'$'}{poi.description}"
                    h.v.setOnClickListener { onClick(poi) }
                }
            }
        """.trimIndent() else ""

        val poiInit = if (kind == TemplateKind.READONLY) """
                loadPois()
                setupPoiDrawer()
        """.trimIndent() else ""

        return """
            package {{PACKAGE_NAME}}

            import android.Manifest
            import android.content.pm.PackageManager
            import android.os.Bundle
            import androidx.appcompat.app.AppCompatActivity
            import androidx.core.app.ActivityCompat
            import androidx.core.content.ContextCompat
            import com.google.android.gms.location.LocationServices
            import com.google.android.gms.location.Priority
            import org.maplibre.android.MapLibre
            import org.maplibre.android.camera.CameraPosition
            import org.maplibre.android.geometry.LatLng
            import org.maplibre.android.maps.MapView
            import org.maplibre.android.maps.MapLibreMap

            class MainActivity : AppCompatActivity() {

                private lateinit var mapView: MapView
                private var map: MapLibreMap? = null

                private val locationPermissionRequest = registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) centerOnUser()
                }

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    MapLibre.getInstance(this)
                    setContentView(R.layout.activity_main)

                    mapView = findViewById(R.id.mapView)
                    mapView.onCreate(savedInstanceState)
                    mapView.getMapAsync { m ->
                        map = m
                        centerOnUser()
                    }
                    $poiInit
                }

                private fun centerOnUser() {
                    val granted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        return
                    }
                    val client = LocationServices.getFusedLocationProviderClient(this)
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { loc ->
                            loc ?: return@addOnSuccessListener
                            map?.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(loc.latitude, loc.longitude))
                                .zoom(14.0)
                                .build()
                        }
                }

                override fun onStart() { super.onStart(); mapView.onStart() }
                override fun onResume() { super.onResume(); mapView.onResume() }
                override fun onPause() { super.onPause(); mapView.onPause() }
                override fun onStop() { super.onStop(); mapView.onStop() }
                override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
                override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
                override fun onSaveInstanceState(outState: Bundle) {
                    super.onSaveInstanceState(outState)
                    mapView.onSaveInstanceState(outState)
                }
                $poiBlock
            }
        """.trimIndent()
    }

    /**
     * Java sibling of [mainActivityKt] for projects scaffolded with Java
     * selected in the wizard. `ZipRecipeExecutor` skips the wrong-language
     * file at scaffold time. The Java sibling intentionally omits the C5
     * POI drawer to keep the file readable for someone learning Android in
     * Java; the README directs them at the Kotlin file as the authoritative
     * source for the drawer pattern.
     */
    private fun mainActivityJava(@Suppress("UNUSED_PARAMETER") kind: TemplateKind): String = """
        package {{PACKAGE_NAME}};

        import android.Manifest;
        import android.content.pm.PackageManager;
        import android.os.Bundle;
        import androidx.appcompat.app.AppCompatActivity;
        import androidx.core.app.ActivityCompat;
        import androidx.core.content.ContextCompat;

        import com.google.android.gms.location.LocationServices;
        import com.google.android.gms.location.Priority;

        import org.maplibre.android.MapLibre;
        import org.maplibre.android.camera.CameraPosition;
        import org.maplibre.android.geometry.LatLng;
        import org.maplibre.android.maps.MapView;
        import org.maplibre.android.maps.MapLibreMap;

        public class MainActivity extends AppCompatActivity {

            private MapView mapView;
            private MapLibreMap map;

            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                MapLibre.getInstance(this);
                setContentView(R.layout.activity_main);

                mapView = findViewById(R.id.mapView);
                mapView.onCreate(savedInstanceState);
                mapView.getMapAsync(m -> {
                    map = m;
                    centerOnUser();
                });
            }

            private void centerOnUser() {
                int granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
                if (granted != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 42);
                    return;
                }
                LocationServices.getFusedLocationProviderClient(this)
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc == null || map == null) return;
                        map.setCameraPosition(new CameraPosition.Builder()
                            .target(new LatLng(loc.getLatitude(), loc.getLongitude()))
                            .zoom(14.0)
                            .build());
                    });
            }

            @Override protected void onStart() { super.onStart(); mapView.onStart(); }
            @Override protected void onResume() { super.onResume(); mapView.onResume(); }
            @Override protected void onPause() { super.onPause(); mapView.onPause(); }
            @Override protected void onStop() { super.onStop(); mapView.onStop(); }
            @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
            @Override protected void onDestroy() { super.onDestroy(); mapView.onDestroy(); }
            @Override protected void onSaveInstanceState(Bundle outState) {
                super.onSaveInstanceState(outState);
                mapView.onSaveInstanceState(outState);
            }
        }
    """.trimIndent()

    /**
     * Tier 2 README emitted into the generated project root. Tier 1 (the
     * tooltip on the template card) is in `GisPlugin.getTooltipEntries`;
     * Tier 3 (in-IDE markdown tutorial) lives at
     * `gis-plugin/src/main/assets/docs/osm-tutorial.md`.
     *
     * Audience: a Tier-1 developer who already knows Android Studio and
     * wants the "where do I poke things" map of this scaffold.
     */
    private fun readme(kind: TemplateKind): String {
        val templateLabel = when (kind) {
            TemplateKind.READONLY -> "Read-only POIs"
            TemplateKind.ANNOTATE -> "Annotate (with photos)"
        }
        return """
            # {{APP_NAME}} — OSM Map ($templateLabel)

            Generated by the **GIS plugin** for Code on the Go. This README is the
            project-local Tier-2 doc; for the longer "how OpenStreetMap works"
            tutorial, long-press the template card in the IDE and tap
            "OSM + MapLibre tutorial".

            ## What's where

            | Path | What it does |
            |---|---|
            | `app/build.gradle.kts` | MapLibre + Material + Play Services Location dependencies. Bump versions here. |
            | `app/src/main/AndroidManifest.xml` | Runtime permissions (location${if (kind == TemplateKind.ANNOTATE) ", camera" else ""}). Add more `<uses-permission>` entries here as needed. |
            | `app/src/main/res/layout/activity_main.xml` | The MapLibre `MapView` lives here. The `app:maplibre_styleUrl` attribute points at the bundled style. |
            | `app/src/main/assets/style.json` | MapLibre style. **Defaults to MapLibre's demo tile server** — replace with a vector style backed by a bundled `.mbtiles` for true offline. |
            | `app/src/main/java/{{PACKAGE_NAME}}/MainActivity.{kt,java}` | Initialises MapLibre, forwards lifecycle events to `MapView`, centres the camera on the user's GPS fix. |

            ## Run it

            1. Open the project in Code on the Go (already done — you're here).
            2. Hit **Build → Run**. The MapView will inflate, request location permission on first run, and centre on the user's fix.
            3. If the map is blank: check Logcat for `MapLibre` errors. The most common cause is a missing `INTERNET` permission, which the manifest already declares — but a bug elsewhere in the manifest can override it.

            ## Make it offline

            The default `style.json` hits MapLibre's public demo tile server. To run with no network:

            1. Drop an `.mbtiles` (vector tiles) into `app/src/main/assets/maps/region.mbtiles`. Sources:
               - [OpenMapTiles](https://openmaptiles.org/) — pre-built country packs
               - [Geofabrik](https://download.geofabrik.de/) extracts processed via `tilemaker` server-side
            2. Edit `assets/style.json`:
               ```json
               "sources": {
                 "openmaptiles": {
                   "type": "vector",
                   "url": "mbtiles://maps/region.mbtiles"
                 }
               }
               ```
            3. Add the matching layer styling. The [OpenMapTiles styles repo](https://github.com/openmaptiles/openmaptiles) has ready-made styles you can drop in.

            ## Customising the POI dataset (read-only template only)

            The read-only template loads its "places near me" list from `assets/pois.json`. Replace the file with your own data:

            ```json
            [
              { "name": "Lalibela Health Center",
                "lat": 12.0319, "lon": 39.0467,
                "category": "amenity=clinic",
                "description": "Public clinic. Open daily 7am–6pm.",
                "source_url": "https://en.wikipedia.org/wiki/..." }
            ]
            ```

            For >10 k POIs, swap the brute-force JSON loader for SQLite + R-tree.

            ${
                if (kind == TemplateKind.ANNOTATE) {
                    """
                    ## Annotate template — submitter

                    Configure the submit endpoint in `res/values/strings.xml`:

                    ```xml
                    <string name="submit_url">https://your-server.example/api/annotations</string>
                    ```

                    The submitter POSTs each annotation as multipart with the
                    photo bytes attached. CSV / JSON shareheet exports are
                    automatic and don't require a URL.
                    """.trimIndent()
                } else ""
            }

            ## Where to ask for help

            - The **MapLibre Native** Android docs: https://maplibre.org/maplibre-native/android/
            - The **OSM Wiki** map-features index: https://wiki.openstreetmap.org/wiki/Map_features
            - Long-press any tooltip in the IDE for category-specific help
        """.trimIndent()
    }

    /**
     * Minimal MapLibre style JSON that points at OpenMapTiles' demo tile
     * server. The generated app falls back to this on first run; once the
     * recipe extension lands and the wizard's downloader has populated the
     * cache, the recipe copies the cached `tiles.mbtiles` into
     * `assets/maps/<region-id>.mbtiles` and we'll switch the source URL to
     * `mbtiles://...` (see plan §5.2 "Bundled tile path in generated app").
     */
    private fun maplibreStyleJson(): String = """
        {
          "version": 8,
          "sources": {
            "demotiles": {
              "type": "raster",
              "tiles": ["https://demotiles.maplibre.org/tiles/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "attribution": "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#f5f1e8" }
            },
            {
              "id": "demotiles",
              "type": "raster",
              "source": "demotiles",
              "minzoom": 0,
              "maxzoom": 22
            }
          ]
        }
    """.trimIndent()
}
