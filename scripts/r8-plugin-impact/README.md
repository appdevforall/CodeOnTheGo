# R8 plugin-impact analysis (ADFA-5156)

**What this is:** tooling to prove whether a release build of the IDE strips
Kotlin stdlib members that plugins need at runtime.

**Why it exists:** plugins are loaded *parent-first* through a stock
`DexClassLoader` (`PluginLoader.kt:92-116`, parent passed at
`PluginManager.kt:603`), so every `kotlin.**` class a plugin references resolves
from **the IDE's dex**, not from the ~1058 stdlib classes the plugin bundles.
R8 cannot see plugin call sites, so it strips every stdlib member the IDE itself
does not call. The net effect is that **a plugin can only call the subset of the
Kotlin standard library that the IDE also calls**; anything else throws
`NoSuchMethodError` at runtime.

That failure is invisible at build time. `assemblePlugin` is green, the manifest
is fine, the `.cgp` is correct. Only on-device execution of the specific code
path reveals it — which is why this tooling exists.

ADFA-5156 rolled R8 shrinking back (restored `-dontshrink` in
`app/proguard-rules.pro`) as a stopgap. **Any future attempt to re-enable
shrinking must be validated with these scripts before shipping.**

## Usage

Nothing here has third-party dependencies; the Python is stdlib-only.

```bash
# 1. Dump a release APK's dex (do this for the build you want to check,
#    and for a reference build to compare against)
./dex-dump.sh /path/to/CodeOnTheGo-v8-release.apk out/candidate
./dex-dump.sh /path/to/previous-release.apk        out/reference

# 2. Dump every plugin's dex (.cgp files are zips)
./dex-dump.sh --disassemble /path/to/plugins/*.cgp out/plugins

# 3. Compare
uv run --no-project analyze-plugin-impact.py impact \
	out/reference/full-dump.txt out/candidate/full-dump.txt out/plugins
```

To pull the reference APK off a device:

```bash
adb shell pm path com.itsaky.androidide            # -> /data/app/.../base.apk
adb pull <that path> baseline.apk
```

## Reading the output

Each `kotlin.*`/`kotlinx.*` call site originating in a plugin's own code is
classified by walking the superclass/interface chain in the host dex:

| verdict | meaning |
|---|---|
| `ok` | class present in the IDE dex, method found in its hierarchy |
| `NoSuchMethod` | class present but method absent. **Guaranteed runtime failure.** This is the ADFA-5156 bug |
| `CLASS_ABSENT` | class missing from the IDE dex entirely, so the plugin's own bundled copy loads instead |

Calls originating *inside* a plugin's bundled stdlib copy are excluded — that
copy is shadowed at runtime, so they are not real call sites.

`CLASS_ABSENT` is not automatically a bug. It is how a plugin's own copy gets
used, and it is fine when the whole subtree is absent. It is dangerous when a
class is absent but its **supertype is present-and-stripped** in the host: the
chain jumps back into the host and dies. That is exactly the confusing shape in
the original report (`ArraysKt` facade dropped, so it loaded from the plugin's
`classes2.dex`, but its superclass `ArraysKt___ArraysKt` resolved parent-first
back into the IDE's stripped copy). Use `explain-absent` to inspect.

## Known false positives — read before acting on results

**Methods inherited from the Android boot classpath are reported as
`NoSuchMethod`.** `java.util.*`, `java.lang.*` and friends are not in the APK,
so the hierarchy walk runs off the end of what it can see and gives up. Known
instances, all benign:

- `AbstractMutableSet.addAll` / `containsAll` / `removeAll` / `retainAll` -> `java.util.AbstractSet`
- `AbstractMutableMap.putAll` -> `java.util.AbstractMap`
- `IntIterator.hasNext` / `LongIterator.hasNext` -> `java.util.Iterator`
- `ArrayDeque.iterator` -> `java.util.AbstractList`

Before treating any `NoSuchMethod` as real, run `explain-method` on it and check
whether the chain exits the APK at a `java.*` link. If it does, it is a false
positive.

**Kotlin multifile facades declare nothing themselves.** `ArraysKt`,
`StringsKt`, `CollectionsKt` etc. extend a part class (`ArraysKt___ArraysKt`,
`StringsKt__StringsKt` — note the varying underscore counts) which holds the
actual members. Checking a facade directly for a method always fails. The
hierarchy walk handles this; ad-hoc greps do not.

**D8 build-time synthetics never exist in the host.** Classes like
`kotlin.UByte$$ExternalSyntheticBackport0` and
`kotlin.io.path.PathTreeWalk$$ExternalSyntheticApiModelOutline0` are generated
during the *plugin's* own dexing. They show as `CLASS_ABSENT` and always will;
they are leaf synthetics with no host counterpart, so they carry no split-brain
risk.

## Baseline from ADFA-5156

Measured 2026-08-15 across 24 plugins, 3,991 call sites, comparing the shipped
R8-shrunk release against the `-dontshrink` rollback:

| | shipped (shrinking on) | rolled back |
|---|---:|---:|
| resolves cleanly | 2,630 | 3,831 |
| guaranteed `NoSuchMethodError` | 74 (66 real + 8 false positives) | 8 (all false positives) |
| falls through to plugin dex | 1,287 | 152 (3 D8 synthetics) |

Zero regressions. Ten plugins carried guaranteed-failure call sites in the
shipped build: compose-preview 36, sketch-to-ui 20, client-time-tracker 5,
random-xkcd 5, markdown-previewer 2, project-to-template 2, and ai-assistant /
ai-literacy-course / keystore-generator / layout-editor 1 each.

**A re-enabled-shrinking build should be measured against these numbers.** The
target is 0 real `NoSuchMethod` across all plugins, not just the one that
happened to get reported.
