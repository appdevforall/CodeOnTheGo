# `.cgb` add-on bundle format

- **Ticket:** [ADFA-5639](https://appdevforall.atlassian.net/browse/ADFA-5639)
- **Status:** Draft, for review
- **Date:** 2026-11-21

## Problem

An add-on often needs more than one artifact: a plugin plus the templates that use it, or several template collections. Today the only way to ship them together is to write an installer plugin whose job is to register templates from its own assets. `pebble-custom-function-template-installer` (addons) and `FandGPlugin` both do this through `templateService.createTemplateBuilder(...)` / `registerTemplate(...)`. That approach:

- makes a template author write, build and sign Kotlin to ship templates;
- runs code at plugin load just to copy files, and needs an IDE restart before the templates appear (the PCF installer's README says so);
- gives the templates full plugin trust, though they carry no code of their own.

A `.cgb` bundle is one file that carries several existing add-ons and installs each one as it would be installed on its own.

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

Members are the same files that would be installed on their own, byte for byte. A bundle adds packaging; it does not change what a `.cgp` or `.cgt` is.

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
    ├── fun-and-games-jme.cgt ◄──────── type "templates", sha256, ... ──────┤
    │   └── (same layout as above)                                          │
    │                                                                       │
    └── fun-and-games.cgp ◄──────────── type "plugin", sha256, id, version ─┘
        │  an APK, unchanged
        ├── AndroidManifest.xml            plugin.id / plugin.version
        ├── classes.dex                    meta-data, checked against
        ├── resources.arsc                 bundle.json
        ├── assets/
        └── META-INF/                      the plugin's own signature
```

Two layers of trust:

| Layer | Covers | Checked today? |
|---|---|---|
| Bundle signature (`META-INF/` at the root) | Every other entry in the bundle, through `MANIFEST.MF` | No. Needs the ADFA publisher key from ADR 0020 (proposed, not yet on `stage`). |
| Member signature (inside each `.cgp`) | That plugin's code | No. `PluginManager.loadPlugin` only checks that a signature exists, and logs "Continue anyway for development" when it is invalid. Enforcement also comes with ADR 0020. |

**Install order:** templates first, then plugins, and within each type the order of `members` in `bundle.json`. A plugin that looks up its templates at load then finds them.

### `bundle.json`

```json
{
  "formatVersion": 1,
  "id": "org.appdevforall.pebblecustomfunctions",
  "name": "Pebble Custom Functions",
  "version": "1.2.0",
  "author": "App Dev For All",
  "description": "Builder and example templates for Pebble Custom Functions.",
  "minAppVersion": "26.40",
  "members": [
    {
      "type": "templates",
      "path": "addons/pcf-templates.cgt",
      "id": "org.appdevforall.pcftemplates",
      "version": "1.2.0",
      "sha256": "41ab..."
    },
    {
      "type": "plugin",
      "path": "addons/pcf-runtime.cgp",
      "id": "org.appdevforall.pcfruntime",
      "version": "1.0.0",
      "sha256": "9f2c..."
    }
  ]
}
```

| Field | Rule |
|---|---|
| `formatVersion` | Integer. The app refuses a bundle whose version it does not know and asks the user to update. |
| `id` | Reverse-DNS, the same style as `addon_id` in `cgt-build.properties`. Identifies the bundle for upgrade and uninstall. |
| `version` | Semver. |
| `minAppVersion` | Optional. The same name and format as addons' `addon.json`: a `YY.ww` Code on the Go release with an optional `.patch`, e.g. `26.40`. Compared numerically, part by part, with a missing patch counting as 0. A build with no release version (`next_release_version` empty, as in local debug builds) skips the check. |
| `members[].type` | `plugin` or `templates`. An unknown type fails the whole bundle, as for `formatVersion`: skipping a member could install a plugin without the templates it expects. |
| `members[].path` | Relative to the archive root, under `addons/`. No `..`, no absolute paths, no nested `.cgb`. The extension must match `type`: `.cgp` for `plugin`, `.cgt` for `templates`. Unique across members, compared case-insensitively. |
| `members[].id` / `version` | Non-empty, and equal to the member's own values, read raw from the member, not as a loader resolves them: the `plugin.id` / `plugin.version` meta-data in a `.cgp`'s manifest, and `addon_id` / `version` in a `.cgt`'s `cgt-build.properties`. A missing or empty value fails the bundle; `PluginLoader`'s fallback to the package name and `1.0.0` does not apply. So a `.cgt` made by `CgtTemplateBuilder`, which writes no `cgt-build.properties`, cannot be a member until it is built with addons' `build-cgt.sh` and a non-empty id. Ids are unique across members. |
| `members[].sha256` | Hex digest of the member file. Checked before install. |

### Archive rules

The installer reads the archive only through its central directory (`ZipFile`), never by streaming it. A ZIP can hold two entries with the same name; `ZipFile.getEntry` returns the last one and `ZipInputStream` returns both, so a validator that hashed one copy and an installer that read the other would bypass the digest.

- **Allowed entries, exactly:** `bundle.json`; `LICENSE`; the listed members; `META-INF/MANIFEST.MF` with one signer's `.SF` and `.RSA` or `.EC`; and directory entries that are prefixes of those paths. Anything else fails the bundle, so nothing travels unseen inside it.
- **Duplicate entry names**, compared case-insensitively, fail the bundle.
- **Limits:** at most 256 entries, 32 members, 64 KiB for `bundle.json`, and 1 GiB uncompressed in total. Three plugins make up about two-thirds of a ~530 MB full addons build (addon distribution PRD, P01 and P03), roughly 120 MB each, so 1 GiB fits several. Each member's own contents are then limited by its installer.

## Install

1. **Receive.** `.cgb` joins `.cgp` and `.cgt` in the `ExternalFileInstallActivity` intent filters, in both cases (`.cgb`, `.CGB`). The add-ons manager's Download picker is per tab (`ManagerScreen.kt`); both the Plugins and Templates tabs list `.cgb`, since a bundle can carry either type.
2. **Validate everything first:** the archive rules, the `bundle.json` schema, `minAppVersion`, every digest, every member's id and version, each member's own validity (a `.cgp` that `PluginLoader` would reject fails the bundle), and, for a plugin member whose id is already installed, that its signer matches the installed one. Nothing is written to the add-on directories until all of this passes.
3. **Confirm once.** One dialog lists the bundle's name, author and version, then each member with its type and what will happen to it: install, upgrade, or keep the installed version (see Versions). There is no per-member conflict dialog.
4. **Snapshot.** Before installing anything, copy every installed member that this bundle will replace.
5. **Install members** in install order, through these operations:
   - **Templates:** a `.cgt` member is stored as `<addon_id>.cgt`. To find an installed one, the installer reads `addon_id` back from that file's `cgt-build.properties`; nothing in CoGo reads that file today, so this reader is new. If a template file with that name exists but has a different or missing `addon_id`, the bundle fails rather than overwriting it. The current paths key on the file name: `TemplateRepositoryImpl.installTemplate` refuses a name that exists, and `installCollection` overwrites it.
   - **Plugins:** a new suspend operation in `plugin-manager` that returns a result, with no UI, used by both the bundle installer and `PluginManagerViewModel`. It holds the install-conflict and same-signer checks that live only in the view model's `resolveInstallConflict` today. `PluginRepositoryImpl.installPluginFromFile` uninstalls any plugin with the same id and copies the new file without comparing signers, so calling it directly would let a bundle replace a plugin that a standalone install refuses. A different signer on a matching id fails the bundle.
6. **Roll back on failure.** If any member fails, delete the members this run newly installed, and restore every snapshot from step 4, including the one for the member that failed. Snapshots are deleted once the whole bundle succeeds. The user sees which member failed.
7. **Record the bundle** (see Bundle record).

If a plugin member needs the IDE restarted, the bundle asks once, after every member is in place.

## Failure policy

| Check | Result when it fails |
|---|---|
| Archive rules, `bundle.json` schema, `formatVersion`, `minAppVersion` | Reject the bundle |
| A digest, id or version mismatch; a missing or empty member id or version | Reject the bundle |
| A different signer on an installed plugin id; a template file-name clash | Reject the bundle |
| The bundle signature, or a member's signature, does not verify against the ADFA key | The unverified-install consent screen (ADR 0020) |

Integrity and identity failures always reject. Only a signature failure is something the user can choose to accept.

## Versions

- **Bundle version.** A higher `version` than the installed bundle with the same `id` is an upgrade. The same version is offered as a reinstall, which restores members that were removed individually. A lower version is refused: "A newer version of this bundle is installed."
- **Member version.** A member that is not installed is installed. One installed at a lower version is upgraded. One installed at the same or a higher version is kept, and the confirm dialog says so; a bundle never downgrades a member, even inside a higher bundle version.
- **Shared members.** Two bundles may list the same member at different versions. The installed copy is the highest any install brought. A bundle counts as complete when each of its members is installed at or above the version it lists.

## Bundle record

For each installed bundle: its `id` and `version`, and for each member its `type`, `id`, the `version` the bundle lists, and `installedByBundle`, which is true only if this bundle installed the member when it was not already present. Storage is ADFA-5642's choice; ADR 0001 applies.

The add-ons manager uses the record to show a bundle as one entry with its members underneath.

## Upgrade and uninstall

- **Upgrade:** a member the new bundle version no longer lists is removed only if `installedByBundle` is true for it and no other installed bundle lists it.
- **Uninstall:** removing a bundle removes only the members it installed (`installedByBundle` true) that no other installed bundle lists. A member the user installed on their own stays: `PluginManager.uninstallPlugin` also clears a plugin's state and caches, so removing it would lose more than the file.
- **Removing one member:** a member can still be uninstalled on its own. The bundle then shows as incomplete, and reinstalling it restores the member.

## Signing

Signing waits on ADR 0020 (add-on trust model, proposed). Until that lands there is no ADFA publisher key and no consent screen, so a bundle is checked for integrity and identity only, like any add-on installed today.

Once ADR 0020 lands:

- **The bundle is JAR-signed** by the pinned ADFA publisher certificate. Every entry outside `META-INF/`, `LICENSE` included, must be covered by that signature. An unsigned entry fails the signature check.
- **Each member keeps its own signature,** verified as when it is installed alone.
- **Verified means every signature verifies,** the bundle's and each plugin member's. If any signature check fails, the whole bundle goes through the unverified-install consent screen. It is never marked as partly verified.

## Out of scope

- Other member types (snippets, code actions, documentation packs). `formatVersion` and `type` leave room for them.
- Dependencies between bundles, and downloading members that the bundle does not contain.
- Converting the existing installer plugins. Once bundles ship, `pebble-custom-function-template-installer` and `FandGPlugin` can become `.cgb` files in the addons repository, as separate tickets.

## Open questions

1. **All-or-nothing install.** Snapshot and rollback (Install steps 4 and 6) are the main implementation cost. The alternative is to keep whatever installed and report the failure, which is simpler but can leave a plugin without its templates.
2. **Hal's requirements.** The ticket comment notes that Hal has strong views on this format; they are not recorded yet and should be reconciled with this draft before implementation.
