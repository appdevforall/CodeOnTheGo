# `.cgb` add-on bundle format

- **Ticket:** [ADFA-5639](https://appdevforall.atlassian.net/browse/ADFA-5639)
- **Status:** Draft, for review
- **Date:** 2026-11-21

## Problem

An add-on often needs more than one artifact: a plugin plus the templates that use it, or several template collections. Today the only way to ship them together is to write an installer plugin whose job is to register templates from its own assets. `pebble-custom-function-template-installer` (addons) and `FandGPlugin` both do this through `templateService.createTemplateBuilder(...)` / `registerTemplate(...)`. That approach:

- makes a template author write, build and sign Kotlin to ship templates;
- runs code at plugin load just to copy files, and needs an IDE restart before the templates appear (the PCF installer's README says so);
- gives the templates full plugin trust, though they carry no code of their own.

A `.cgb` bundle is one file that carries several existing add-ons and installs each through the path it already uses.

## Format

A `.cgb` file is a ZIP archive. The ticket first named the extension `.cg`, but `.cg` is already the IDE's data and per-project cache directory (`IDE_DATA_DIR_NAME`, `PROJECT_CACHE_DIR_NAME`), so bundles use `.cgb`, which also matches `.cgp` and `.cgt`.

```
my-addon.cgb
├── bundle.json          required, at the root
├── LICENSE              optional
└── addons/
    ├── my-plugin.cgp    an unmodified .cgp
    └── my-templates.cgt an unmodified .cgt
```

Members are the same files that would be installed on their own, byte for byte. A bundle adds packaging; it does not change what a `.cgp` or `.cgt` is, so every existing validator, installer and signature check applies to each member unchanged.

### Example: Fun and Games

The Fun and Games plugin and its two template collections, shipped today as an installer plugin, become one bundle. 
```
fun-and-games.cgb                        ZIP, JAR-signed by ADFA
│
├── META-INF/                            bundle signature
│   ├── MANIFEST.MF                        digest of every entry below
│   ├── ADFA.SF
│   └── ADFA.RSA
│
├── bundle.json                          manifest: lists every member ──────┐
├── LICENSE                                                                 │
│                                                                           │
└── addons/                                                                 │
    │                                                                       │
    ├── fun-and-games.cgp ◄──────────── type "plugin", sha256, id, version ─┤
    │   │  an APK, unchanged                                                │
    │   ├── AndroidManifest.xml            plugin.id / plugin.version       │
    │   ├── classes.dex                    meta-data, checked against       │
    │   ├── resources.arsc                 bundle.json                      │
    │   ├── assets/                                                         │
    │   └── META-INF/                      the plugin's own signature       │
    │                                                                       │
    ├── fun-and-games-libgdx.cgt ◄───── type "templates", sha256, ... ──────┤
    │   │  a template collection, unchanged                                 │
    │   ├── templates.json                 lists the template folders       │
    │   ├── cgt-build.properties           addon_id / version, checked      │
    │   ├── bubblewand/                    against bundle.json              │
    │   │   ├── template/template.json                                      │
    │   │   └── build.gradle, settings.gradle.peb, ...                      │
    │   ├── demo/                                                           │
    │   └── tetris/                                                         │
    │                                                                       │
    └── fun-and-games-jme.cgt ◄──────── type "templates", sha256, ... ──────┘
        └── (same layout as above)
```

Two layers of trust, checked independently:

| Layer | Covers | Checked by |
|---|---|---|
| Bundle signature (`META-INF/` at the root) | `bundle.json`, so every member's digest and identity | The bundle installer |
| Member signature (inside each `.cgp`) | That plugin's code | The existing plugin installer, unchanged |

Installation order follows from the manifest: both `.cgt` members, then `fun-and-games.cgp`.

### `bundle.json`

```json
{
  "formatVersion": 1,
  "id": "org.appdevforall.pebblecustomfunctions",
  "name": "Pebble Custom Functions",
  "version": "1.2.0",
  "author": "App Dev For All",
  "description": "Builder and example templates for Pebble Custom Functions.",
  "minIdeVersion": "2.3.0",
  "members": [
    {
      "type": "plugin",
      "path": "addons/pcf-runtime.cgp",
      "id": "org.appdevforall.pcfruntime",
      "version": "1.0.0",
      "sha256": "9f2c..."
    },
    {
      "type": "templates",
      "path": "addons/pcf-templates.cgt",
      "id": "org.appdevforall.pcftemplates",
      "version": "1.2.0",
      "sha256": "41ab..."
    }
  ]
}
```

| Field | Rule |
|---|---|
| `formatVersion` | Integer. The app refuses a bundle whose version it does not know and asks the user to update. |
| `id` | Reverse-DNS, the same style as `addon_id` in `cgt-build.properties`. Identifies the bundle for upgrade and uninstall. |
| `version` | Semver. |
| `minIdeVersion` | Optional. Checked before anything installs. |
| `members[].type` | `plugin` (`.cgp`) or `templates` (`.cgt`). An unknown type fails the whole bundle, as for `formatVersion`: skipping a member could install a plugin without the templates it expects. |
| `members[].path` | Relative to the archive root, under `addons/`. No `..`, no absolute paths, no nested `.cgb`. |
| `members[].id` / `version` | Must equal the member's own: `plugin.id` / `plugin.version` meta-data for a `.cgp`, `addon_id` / `version` in `cgt-build.properties` for a `.cgt`. A mismatch fails the bundle. |
| `members[].sha256` | Hex digest of the member file. Checked before install. |

Entries in the archive that `bundle.json` does not list, other than `LICENSE`, fail the bundle, so nothing travels unseen inside it.

## Install

1. **Receive.** `.cgb` joins `.cgp` and `.cgt` in the `ExternalFileInstallActivity` intent filters, in both cases (`.cgb`, `.CGB`), and in the add-ons manager's file picker.
2. **Validate everything first.** Limits on entry count and total uncompressed size; path checks (zip-slip); `bundle.json` schema; every digest; every member's id and version; each member's own validity (a `.cgp` that `PluginLoader` would reject fails the bundle). Nothing is written to the add-on directories until all of this passes.
3. **Confirm once.** One dialog lists the bundle's name, author and version, then each member with its type, version, and whether it is new, an upgrade or a downgrade. Any id conflict is resolved here, not per member.
4. **Install members** through the existing paths: `.cgt` through the template install path, `.cgp` through `PluginManagerViewModel.installPlugin`. Templates go first, so a plugin that looks up its templates at load finds them.
5. **Roll back on failure.** If a member fails, members this run installed are uninstalled and members it upgraded are restored from the copy kept in step 4. The user sees which member failed.
6. **Record the bundle.** A record maps the bundle id and version to its member ids, so the add-ons manager can show the bundle as one entry with its members underneath.

If a plugin member needs the IDE restarted, the bundle asks once, after every member is in place.

## Upgrade and uninstall

- **Upgrade:** a bundle with the same `id` and a higher `version` replaces it. A member the new version no longer lists is uninstalled, unless another installed bundle also lists it.
- **Uninstall:** removing the bundle removes its members, except any another installed bundle also lists. A member can still be uninstalled on its own; the bundle then shows as incomplete, and reinstalling the bundle restores it.

## Signing

This follows the publisher trust model proposed in ADR 0020 (draft, add-on security).

- **Each member keeps its own signature.** A `.cgp` is verified exactly as when installed alone.
- **The bundle is signed with JAR signing** (`META-INF/MANIFEST.MF`, `*.SF`, `*.RSA`) by the same ADFA publisher key. Because `bundle.json` holds every member's digest, a valid signature over `bundle.json` covers the whole bundle.
- **Verified means everything is verified:** the bundle signature and every member's. If any check fails, the whole bundle goes through the unverified-install consent screen. It is never marked as partly verified.

## Out of scope

- Other member types (snippets, code actions, documentation packs). `formatVersion` and `type` leave room for them.
- Dependencies between bundles, and downloading members that the bundle does not contain.
- Converting the existing installer plugins. Once bundles ship, `pebble-custom-function-template-installer` and `FandGPlugin` can become `.cgb` files in the addons repository, as separate tickets.

## Open questions

1. **All-or-nothing install.** Rollback in step 5 is the main implementation cost. The alternative is to keep whatever installed and report the failure, which is simpler but can leave a plugin without its templates.
2. **Hal's requirements.** The ticket comment notes that Hal has strong views on this format; they are not recorded yet and should be reconciled with this draft before implementation.
