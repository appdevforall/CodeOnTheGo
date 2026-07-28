# Does Quick Build work on the apps users actually create?

Status: every built-in CoGo template walked on device on 2026-07-23, seven bugs
found, all seven fixed and device-verified — the last of them (Bug 9) on
2026-07-28.

Provenance tags are mandatory: `[measured on a56]`, `[measured on host]`,
`[inferred]`, `[unmeasured]`. Untagged prose is code reading.

Evidence, in the benchmark repo: `results/20260723T035300Z-stage2-templates`
(the first sweep, before the fix), `…/20260723T121800Z-post-fix-resweep` (the
per-combination matrix), `…/20260723T231345Z-e2e-device-sweep2` (the save→live
distribution), `…/20260723T143600Z-stage3c-room-ksp`. In this repo:
`quick-build/corpus/results/20260728T063125Z-bug9-navfrag-verify`,
`…/20260728T064805Z-consolidated-verify`,
`…/20260728T113213Z-task32-roomksp-online`. Samsung A56 throughout.

## What "every template" means

CoGo ships nine templates in `assets/core.cgt`. Seven declare a `language`
parameter and so exist in both Kotlin and Java; two are single-language. That
is **16 template x language combinations**, and all 16 were walked:

| Template | Languages | viewBinding |
|---|---|---|
| Basic Activity | Kotlin, Java | yes |
| Bottom Navigation Activity | Kotlin, Java | yes |
| Empty Activity | Kotlin, Java | yes |
| Navigation Drawer | Kotlin, Java | yes |
| No Activity | Kotlin, Java | yes |
| No AndroidX | Kotlin, Java | yes |
| Tabbed Activity | Kotlin, Java | yes |
| Compose Activity | Kotlin only | no |
| CodeOnTheGo Plugin | Kotlin only | no |

The seven viewBinding templates are exactly the seven that Bug 1 broke, which
is why that count keeps recurring.

## The result: 14 of 16

Before the config-cache fix, **the setup build failed on all seven viewBinding
templates** — 14 of the 16 combinations — so Quick Build was unusable on almost
every app a user would create from a template. That was Bug 1, and it is the
one David hit.

After it, and after the six template bugs below: **14 of 16 combinations pass**
`[measured on a56]`.

| Combination | Setup | Launch + edit |
|---|---|---|
| Basic K / Basic J | pass | pass |
| Empty K / Empty J | pass | pass |
| No AndroidX K / No AndroidX J | pass | pass |
| Tabbed K / Tabbed J | pass | pass |
| Bottom Nav K / Bottom Nav J | pass | pass (crashed until Bug 9; verified 07-28) |
| Nav Drawer K / Nav Drawer J | pass | pass (crashed until Bug 9; verified 07-28) |
| Compose K | pass (failed until Bug 7) | provisions + installs; **code-edit timing not captured** |
| CodeOnTheGo Plugin | friendly refusal (Bug 3) | n/a by design — output is a `.cgp`, not an app |
| **No Activity K / No Activity J** | **fail** | n/a by design — no launchable Activity |

Two honest caveats on that table. **Compose** provisions and installs cleanly
after Bug 7, but its code-edit save→live was never measured — device
contamination plus a time cap `[unmeasured]`. And the **Plugin** template is
counted as a pass because refusing correctly *is* the right behavior, not
because Quick Build runs on it.

## How fast, by edit kind

From the merged sweeps, 12 measured loops `[measured on a56]`:

| Edit class | n | p50 | range |
|---|---|---|---|
| Resource (`values.xml`) | 2 | ~470 ms | 294-645 ms |
| Code, warm, Java | 2 | ~676 ms | 575-777 ms |
| Code, warm, Kotlin | 5 | ~1781 ms | 1318-2316 ms |
| Code, first build of a session, Java | 1 | 2986 ms | — |
| Code, first build of a session, Kotlin | 3 | ~11 s | 10.0-11.8 s |

Two things read off it. **Language is the dominant lever** — Kotlin costs
~2.6x Java warm, all of it kotlinc. And **resource edits are the fastest
class**, because an aapt2 relink beats a Kotlin compile; before Bugs 6 and 8
they were the class that crashed.

The reload mechanism itself is **15-87 ms** for code and 41 ms for a resource
edit, in every sample. All the latency is compile. That is the same conclusion
the C107 reached independently, and it is why the performance roadmap is
entirely about compile.

The cold-first-build row is what the background seed at provisioning exists to
hide: it moves that ~11 s Kotlin penalty off the user's first save.

## The seven bugs

Numbering is the ticket's. Bugs 1-3 preceded the sweep; 4-10 are what walking
the templates found. Each was checked against the branch, not against the
status doc.

**Bug 4 — the generated test app crash-looped on launch.** Any androidx-based
app. Quick Build generated a proxy for `androidx.startup.InitializationProvider`
— a library component the daemon never recompiles, so proxying it bought
nothing and broke initialization. Fixed by excluding it (it is one of four
entries in `QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS`).
Device-verified, `20260723T105700Z-bug4-verify`.

**Bug 5 — a resource edit crashed the app and poisoned the session.** The
relink shipped a bare `resources.arsc`, which cannot back a file-typed resource
— a drawable XML, an adaptive-icon mipmap. The reload crashed resolving the
launcher icon, and because a failed relink leaves the dirty delta uncleared,
every subsequent edit re-failed. Fixed by shipping the **full relinked resource
apk**. Device-verified, `20260723T111950Z-bug5-verify`.

**Bug 6 — resource ids shifted between baseline and relink.** A relink that
reassigned type indices left already-loaded code referencing the old numbers.
Fixed by feeding AGP's `stable_resource_ids_file` to `aapt2 link --stable-ids`,
so ids stay pinned to the baseline. Device-verified,
`20260723T140211Z-bug678-verify`; the trigger is a regression test.

**Bug 7 — the Compose template could not set up at all.** The proxy generator
emitted `Proxy2Activity extends PreviewActivity`, and Compose's `PreviewActivity`
is `final` — `error: cannot inherit from final`. Fixed twice over: the class is
excluded by name, and `ComponentProxiabilityResolver` now generalizes the check
by reading the `ACC_FINAL` flag out of the class file (never loading the class),
which also caught Room's `MultiInstanceInvalidationService` before it broke a
real project.

**Bug 8 — library-provided resources failed to link.** Material3 themes, a
library manifest reference. The hot relink had no access to the libraries'
compiled resources. Fixed by feeding the setup build's compiled library
resource units (`.flat` files) to `aapt2 link` as overlays. Device-verified,
`20260723T140211Z-bug678-verify`.

**Bug 9 — Navigation-Component templates crashed on first launch.** Bottom
Navigation and Navigation Drawer, both languages, with
`Fragment$InstantiationException` → `ClassNotFoundException` on the destination
fragment. Root cause: `Context#getClassLoader()` is pinned to the base APK's
shell classloader at LoadedApk-attach time, and androidx's default
`FragmentFactory` resolves nav-graph destinations through it — but user classes
live only in the payload dex. The sweep also scoped the bug precisely: **Tabbed
did not hit it**, because `ViewPager2`/`FragmentStateAdapter` instantiate
fragments in code rather than by XML class name. Fixed by having every
generated proxy activity override `getClassLoader()` via
`QuickBuildClassLoaders.forActivity`, so by-name resolution sees the payload
loader.

Verified on device **2026-07-28** across all four affected combinations
`[measured on a56]` — launch clean with the nav-graph Home fragment rendered,
plus a fast-path edit landing live: Bottom Nav Kotlin 2313 ms, Nav Drawer Java
1231 ms, zero rebaselines
(`20260728T063125Z-bug9-navfrag-verify`, re-confirmed for the other two arms in
`20260728T064805Z-consolidated-verify`).

**Bug 10 — the No-Activity template reported a build failure that had not
happened.** The Gradle setup build succeeded and wrote `setup.json`, but with
`entryActivity` null — there is no launchable Activity, by design. `SetupInfo`
parsing rejected that as a missing required field, and the user saw the generic
"Quick Build setup build failed" banner. Fixed: `entryActivity` is now nullable,
and the session refuses with a friendly message instead of faking either
success or a build failure. Verified 2026-07-28: the friendly refusal appears,
the session does not fake `Ready`, and no raw "setup build failed" is emitted.

## What still does not work

**No-Activity projects (2 of the 16).** There is nothing to run, so there is
nothing to hot-reload. Quick Build now says so clearly rather than reporting a
phantom build failure. This is correct behavior, not a defect — it is counted
as a fail above only because those two combinations do not produce a working
Quick Build session.

**Room / KSP apps — and this one is not Quick Build's fault.** Room, KSP, and
in fact every recognized annotation processor's codegen jar (dagger-compiler,
auto-value, Moshi, Glide) are **entirely absent from the bundled offline Maven
repo**. Verified two ways: on device, `find` over CoGo's bundled maven tree
returns zero hits for `room`, `ksp`, or `symbol-processing` `[measured on a56]`;
and in the shipped asset, `assets/localMvnRepository.zip` (1,509 files) contains
no matching entry while a full androidx set is present `[measured on host]`.

The consequence is that such a project **cannot complete its first STANDARD
Gradle setup build offline** — it fails before Quick Build is involved at all.
This is a bundle gap, tracked as backlog #5.

Given one online build, it all works `[measured on a56, 2026-07-28,
`20260728T113213Z-task32-roomksp-online`]`: `:app:kspDebugKotlin` ran on the
phone, the feared sqlite-jdbc query-verifier wall never fired, the generated
`_Impl` classes compiled, and the app launched clean. Both directions of
annotation-impact routing were verified — a body-only edit inside the
`@Database` file stayed on the `CodeOnly` fast path, while adding a field to an
`@Entity` escalated to `ANNOTATION_PROCESSOR_INPUT_CHANGED` and rebaselined in
21.5 s. Whether such a project then works **fully offline** after one online
priming run is still `[unmeasured]` (task #98).

## One caveat on the consolidated pass

The 07-28 consolidated run records 12 of 13 checks green, with
`bug12-add-file` marked FAIL. That one is a **harness assertion, not a product
failure**: `adb push` creates the file and then writes its content, and the two
watcher events landed in separate coalescing batches, so the step produced two
back-to-back `CodeOnly` builds where the driver asserted exactly one. Both
builds were incremental successes with zero invalidations and zero
rebaselines. A real in-IDE save is a single write. Worth knowing because the
same artefact inflates build counts elsewhere in the benchmark data.

## Verdict

**Yes, for the apps CoGo's templates produce — with one bundle gap that is not
Quick Build's to fix.**

Every template that produces a runnable app now provisions, installs, launches
and hot-reloads on a real device, in both languages, with warm saves between
0.3 s and 2.3 s. The two combinations that do not work produce no runnable app
by definition and now say so honestly.

Three things temper that. Compose provisions but its edit loop was never timed,
so the one template class using the most modern UI toolkit is the least
measured. Room-class apps are blocked entirely offline by a missing bundle
dependency, which for an offline-first product is the more serious of the two
gaps in this page. And this sweep tested *templates* — freshly generated,
single-module, small. The 30-app real-world corpus is where the harder cases
live, and it is a separate result.

What this sweep does establish is that the failure mode has changed. Every bug
here was found by walking a user's actual first five minutes, and six of the
seven were invisible to the JVM test suite because they only exist once code
loads from a payload dex on a real device. That is the argument for keeping the
device walk in the loop, not the argument that it is finished.
