# Plugin Authoring Guide

This is the canonical in-repo reference for authoring a Code on the Go
plugin. It covers project layout, the `AndroidManifest.xml` meta-data
contract, theme-aware icons, building, installing, and common failure
modes.

For higher-level architecture and product-side context, see the
[Plugin Development wiki page](https://appdevforall.atlassian.net/wiki/x/BQANIQ).

The closest in-tree example today is `apk-viewer-plugin/`. Note that
the existing sample plugins do **not** yet ship theme-aware icons — the
icon section below is the source of truth until they're updated.

## Project layout

A plugin is a standalone Android module that compiles to a `.cgp` file
(a renamed APK). Recommended layout:

```
my-plugin/
├── build.gradle.kts
├── proguard-rules.pro
├── settings.gradle.kts
└── src/main/
    ├── AndroidManifest.xml          # All plugin metadata lives here
    ├── assets/                      # Theme icons go here (PNG/WebP)
    │   ├── icon_day.png
    │   └── icon_night.png
    ├── kotlin/.../MyPlugin.kt       # Implements IPlugin
    └── res/                         # Standard Android resources (layouts, etc.)
        ├── layout/
        └── values/
```

The build is wired up by applying `com.itsaky.androidide.plugins.build`
in `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "my-plugin"   // becomes my-plugin.cgp
    pluginVersion = "1.0.0"
}
```

## AndroidManifest meta-data reference

All plugin metadata is declared as `<meta-data>` tags inside
`<application>` in `src/main/AndroidManifest.xml`. The loader reads
these in `PluginLoader.getPluginMetadata()`
(`plugin-manager/src/main/kotlin/com/itsaky/androidide/plugins/manager/loaders/PluginLoader.kt:227`).

| Key                       | Type                    | Required | Notes                                                                 |
|---------------------------|-------------------------|----------|-----------------------------------------------------------------------|
| `plugin.id`               | string                  | yes\*    | Unique plugin identifier. Falls back to the APK's package name.       |
| `plugin.name`             | string                  | yes\*    | Display name.                                                         |
| `plugin.version`          | string                  | yes\*    | Defaults to `"1.0.0"`. Prefer `${pluginVersion}` from `pluginBuilder`. |
| `plugin.description`      | string                  | no       | Shown in the plugin list.                                             |
| `plugin.author`           | string                  | no       |                                                                       |
| `plugin.main_class`       | string (FQCN)           | **yes**  | FQCN of your `IPlugin` implementation. Plugin fails to load if absent.|
| `plugin.min_ide_version`  | string                  | yes\*    | Defaults to `"1.0.0"`.                                                |
| `plugin.max_ide_version`  | string                  | no       |                                                                       |
| `plugin.permissions`      | string (comma-separated)| no       | See [Permissions](#permissions).                                      |
| `plugin.dependencies`     | string (comma-separated)| no       | Plugin IDs this plugin requires.                                      |
| `plugin.sidebar_items`    | int                     | no       | Number of sidebar entries this plugin contributes. Default `0`.       |
| `plugin.icon_day`         | string (ZIP path)       | see below| Asset path inside the `.cgp` for the light-theme icon.                |
| `plugin.icon_night`       | string (ZIP path)       | see below| Asset path inside the `.cgp` for the dark-theme icon.                 |

\* The loader supplies a default if the tag is absent, so the install
won't fail, but the plugin will be hard to identify in the UI.

Example (adapted from `apk-viewer-plugin/src/main/AndroidManifest.xml`):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="My Plugin"
        android:theme="@style/PluginTheme">

        <meta-data android:name="plugin.id"              android:value="com.example.myplugin" />
        <meta-data android:name="plugin.name"            android:value="My Plugin" />
        <meta-data android:name="plugin.version"         android:value="${pluginVersion}" />
        <meta-data android:name="plugin.description"     android:value="One-line description." />
        <meta-data android:name="plugin.author"          android:value="Your Name" />
        <meta-data android:name="plugin.main_class"      android:value="com.example.myplugin.MyPlugin" />
        <meta-data android:name="plugin.min_ide_version" android:value="1.0.0" />
        <meta-data android:name="plugin.permissions"     android:value="filesystem.read" />
        <meta-data android:name="plugin.sidebar_items"   android:value="1" />

        <meta-data android:name="plugin.icon_day"        android:value="assets/icon_day.png" />
        <meta-data android:name="plugin.icon_night"      android:value="assets/icon_night.png" />
    </application>
</manifest>
```

`extensions` and `build_actions` are not readable from
`AndroidManifest.xml` meta-data — they're only honored when supplied
via a `plugin.json` fallback manifest. If your plugin needs either,
use the JSON form (see `PluginManifest.kt`).

## Theme-aware icons

The plugin manager renders a different icon based on whether the system
is in light or dark mode (`PluginListAdapter.kt:61`). To opt in, ship
two raster icons in your plugin and point at them from the manifest.

### Where the files go

Put your icons in **`src/main/assets/`**:

```
src/main/assets/
├── icon_day.png
└── icon_night.png
```

**Do not** use `res/drawable/` or `res/raw/`. The loader does a
literal ZIP-entry lookup against the *compiled* `.cgp`
(`PluginLoader.kt:207`), and AAPT2 rewrites resource paths during the
build (vector XML becomes compiled binary XML; bitmaps may move to
density-qualified directories). Only `assets/<name>` is preserved
verbatim, so it's the only location where the path you write in the
manifest matches the path the loader will find.

### Supported formats

- **PNG** (recommended)
- **WebP**
- **JPEG**

**Not supported:** raw SVG, Android vector drawable XML (compiled or
not). Icons are decoded with Glide (`PluginListAdapter.kt:69`), which
handles raster formats only. Convert SVG sources to PNG yourself
before bundling.

### Recommended dimensions

96×96 px square. The plugin list renders icons at roughly 48dp, so
96 px covers xxxhdpi devices with headroom.

### Manifest declaration

```xml
<meta-data android:name="plugin.icon_day"
           android:value="assets/icon_day.png" />
<meta-data android:name="plugin.icon_night"
           android:value="assets/icon_night.png" />
```

The value is the literal path inside the `.cgp` ZIP, **not** an
Android resource reference. No `@drawable/...`, no leading slash.

### Debug builds must ship both icons

If your plugin is built with `assemblePluginDebug` (i.e. the resulting
APK has `FLAG_DEBUGGABLE`), the installer **rejects** the plugin
unless both `plugin.icon_day` and `plugin.icon_night` are declared
**and** both files are present inside the `.cgp`. The check is in
`PluginRepositoryImpl.kt:105`, and the error message is verbatim:

> `[<pluginId>] Missing <keys> for debug plugin. Debug plugins must declare and ship both icon_day and icon_night assets.`

The uploaded `.cgp` is deleted when this fires. Release builds may
omit icons; they fall back to the generic `ic_extension` puzzle-piece
drawable, tinted with `?attr/colorOnSurface` so it adapts to the
theme automatically.

### Runtime location (debugging only)

On install, both icons are extracted to a per-plugin directory on the
device:

```
/data/data/<app-id>/app_plugin_icons/<plugin-id>/icon_day.<ext>
/data/data/<app-id>/app_plugin_icons/<plugin-id>/icon_night.<ext>
```

Useful for confirming the install actually unpacked the files (see
[Troubleshooting](#troubleshooting) below).

## Building & installing

From the repo root, with the flox environment active:

```bash
flox activate -d flox/local -- ./gradlew :my-plugin:assemblePlugin       # release
flox activate -d flox/local -- ./gradlew :my-plugin:assemblePluginDebug   # debug
```

Output:

- Release: `my-plugin/build/plugin/my-plugin.cgp`
- Debug:   `my-plugin/build/plugin/my-plugin-debug.cgp`

To install, transfer the `.cgp` to the device and use the in-app
plugin manager (Settings → Plugins → Install). The installer
validates the manifest, extracts icons, and registers the plugin.

## Permissions

Declare permissions as a comma-separated list in `plugin.permissions`.
Defined in `plugin-api/src/main/kotlin/com/itsaky/androidide/plugins/IPlugin.kt:28`:

| Key                      | Grants                                                                          |
|--------------------------|---------------------------------------------------------------------------------|
| `filesystem.read`        | Read files from the project directory                                           |
| `filesystem.write`       | Write files to the project directory                                            |
| `network.access`         | Access network resources                                                        |
| `system.commands`        | Execute system commands                                                         |
| `ide.settings`           | Modify IDE settings                                                             |
| `project.structure`      | Modify project structure                                                        |
| `native.code`            | Execute native machine code                                                     |
| `ide.environment.write`  | Write to IDE-managed directories (Android SDK, NDK, cache)                      |

## Troubleshooting

**Install fails with "Missing icon_day and icon_night for debug plugin"**

Your debug build is missing one or both icons. Check:
1. Both `<meta-data>` tags are present in `AndroidManifest.xml`.
2. Both files exist in `src/main/assets/`.
3. After building, `unzip -l my-plugin/build/plugin/my-plugin-debug.cgp | grep assets`
   shows both `assets/icon_day.png` and `assets/icon_night.png`.

**Plugin lists shows the generic puzzle-piece icon instead of yours**

The loader couldn't find the file at the declared ZIP path. The
manifest value must match the actual path inside the `.cgp` byte-for-byte.

```bash
# Inspect what the manifest declares:
aapt2 dump xmltree my-plugin/build/plugin/my-plugin.cgp --file AndroidManifest.xml | grep icon_

# Inspect what's actually in the ZIP:
unzip -l my-plugin/build/plugin/my-plugin.cgp | grep -E 'assets|icon'
```

Common causes: typo in the manifest path, file accidentally placed
under `res/raw/` or `res/drawable/`, or a leading slash on the
manifest value (use `assets/icon_day.png`, not `/assets/icon_day.png`).

**Wrong icon shows for the current theme**

The selection happens in `PluginListAdapter.kt:61` via
`isSystemInDarkMode()`. Verify your device is actually in the theme
you expect (system Settings → Display). Also verify both files
extracted to the device:

```bash
adb shell run-as <your.app.id> ls app_plugin_icons/<plugin-id>/
```
