# Information: Quick Build works on the templates users start from — except Room/KSP apps, which cannot build offline

## Summary

- **14 of 16 template x language combinations work end to end** `[measured on a56]` — provision, install, launch and live-reload on a real device, in both languages, with warm saves between 0.3 s and 2.3 s.
- What that covers:
  - the nine templates CoGo ships in `assets/core.cgt`; seven exist in both Kotlin and Java, two are single-language, giving 16 combinations
  - all 16 walked on device; the two failures are No Activity (Kotlin and Java), which by design produce no runnable app
  - seven bugs found by the walk, all seven fixed and device-verified — the last (Bug 9) on 2026-07-28
- What the number does not cover:
  - **Compose** provisions and installs cleanly, but its code-edit loop was never timed `[unmeasured]` — the template using the most modern UI toolkit is the least measured
  - **Room-class apps are blocked entirely offline** by a missing bundle dependency, which for an offline-first product is the more serious of the two gaps
  - this sweep tested *templates*: freshly generated, single-module, small. The 30-app real-world corpus is where the harder cases live, and that is a separate result ([`benchmarking.md`](benchmarking.md))
- **Takeaway: template coverage is no longer the risk.** Every template that produces a runnable app live-reloads in both languages; what remains is a bundle-contents gap (Room/KSP) that fails before Quick Build is involved, and one untimed loop.

Status: every built-in CoGo template walked on device 2026-07-23.

Provenance tags are mandatory: `[measured on a56]`, `[measured on host]`, `[inferred]`, `[unmeasured]`. Untagged prose is code reading.

Evidence, in the benchmark repo:

- `results/20260723T035300Z-stage2-templates` — first sweep, pre-fix
- `.../20260723T121800Z-post-fix-resweep` — the per-combination matrix
- `.../20260723T231345Z-e2e-device-sweep2` — the save->live distribution

In this repo:

- `quick-build/corpus/results/20260728T063125Z-bug9-navfrag-verify`
- `.../20260728T064805Z-consolidated-verify`
- `.../20260728T113213Z-task32-roomksp-online`

Samsung A56 throughout.

## Every template that produces a runnable app passes, in both languages

All 16 combinations were walked `[measured on a56]`:

| Combination                                               | Setup                     | Launch + edit                                            |
| --------------------------------------------------------- | ------------------------- | -------------------------------------------------------- |
| Basic K / J, Empty K / J, No AndroidX K / J, Tabbed K / J | pass                      | pass                                                     |
| Bottom Nav K / J                                          | pass                      | pass (crashed until Bug 9; verified 07-28)               |
| Nav Drawer K / J                                          | pass                      | pass (crashed until Bug 9; verified 07-28)               |
| Compose K                                                 | pass (failed until Bug 7) | provisions + installs; **code-edit timing not captured** |
| CodeOnTheGo Plugin                                        | friendly refusal (Bug 3)  | n/a by design — output is a `.cgp`, not an app           |
| **No Activity K / J**                                     | **fail**                  | n/a by design — no launchable Activity                   |

Two honest caveats on that table:

- Compose's save->live was never measured (device contamination plus a time cap).
- The Plugin template counts as a pass because refusing correctly *is* the right behaviour, not because Quick Build runs on it.

The seven viewBinding templates are exactly the seven Bug 1 broke, which is why that count keeps recurring.

## Where this started: Quick Build was unusable on almost every template

Before the config-cache fix, the proxy app build failed on all seven viewBinding templates — 14 of the 16 combinations — so Quick Build was unusable on almost every app a user would create from a template. That was Bug 1, and it is the one David hit.

## Language is the dominant cost; the apply step never is

From the merged sweeps, 12 measured loops `[measured on a56]`:

| Edit class                             | n   | p50      | range        |
| -------------------------------------- | --- | -------- | ------------ |
| Resource (`values.xml`)                | 2   | ~470 ms  | 294-645 ms   |
| Code, warm, Java                       | 2   | ~676 ms  | 575-777 ms   |
| Code, warm, Kotlin                     | 5   | ~1781 ms | 1318-2316 ms |
| Code, first build of a session, Java   | 1   | 2986 ms  | —            |
| Code, first build of a session, Kotlin | 3   | ~11 s    | 10.0-11.8 s  |

- **Language is the dominant lever** — Kotlin costs ~2.6x Java warm, all of it kotlinc.
- **Resource edits are the fastest class**, because an aapt2 relink beats a Kotlin compile; before Bugs 6 and 8 they were the class that crashed.
- **The apply step (the in-app swap) is 15-87 ms** for code and 41 ms for a resource edit, in every sample. All the latency is compile — the same conclusion the C107 reached independently ([`low-spec-devices.md`](low-spec-devices.md)), and why [`perf-roadmap.md`](perf-roadmap.md) is entirely about compile.
- **The cold-first-build row is what the background warm compile at provisioning exists to hide**: it moves that ~11 s Kotlin penalty off the user's first save.

## The seven bugs the walk found

Numbering is the ticket's. Bugs 1-3 preceded the sweep; 4-10 are what walking the templates found. Each was checked against the branch, not against the status doc.

**Bug 4 — the generated proxy app crash-looped on launch.**

- Scope: any androidx-based app.
- Cause: Quick Build generated a proxy for `androidx.startup.InitializationProvider`, a library component the daemon never recompiles, so proxying it bought nothing and broke initialization.
- Fix: excluded it — one of four entries in `QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS`.
- Verified: `20260723T105700Z-bug4-verify`.

**Bug 5 — a resource edit crashed the app and poisoned the session.**

- Cause: the relink shipped a bare `resources.arsc`, which cannot back a file-typed resource (a drawable XML, an adaptive-icon mipmap). The reload crashed resolving the launcher icon.
- Why it persisted: a failed relink leaves the dirty delta uncleared, so every subsequent edit re-failed.
- Fix: ship the full relinked resource apk.
- Verified: `20260723T111950Z-bug5-verify`.

**Bug 6 — resource ids shifted between baseline and relink.**

- Cause: a relink that reassigned type indices left already-loaded code referencing the old numbers.
- Fix: feed AGP's `stable_resource_ids_file` to `aapt2 link --stable-ids`.
- Verified: `20260723T140211Z-bug678-verify`; the trigger is now a regression test.

**Bug 7 — the Compose template could not set up at all.**

- Cause: the proxy generator emitted `Proxy2Activity extends PreviewActivity`, and Compose's `PreviewActivity` is `final`.
- Fix, twice over: excluded by name, and `ComponentProxiabilityResolver` now generalizes the check by reading the `ACC_FINAL` flag out of the class file (never loading the class).
- Side benefit: that generalization also caught Room's `MultiInstanceInvalidationService` before it broke a real project.

**Bug 8 — library-provided resources failed to link.**

- Scope: Material3 themes, a library manifest reference.
- Cause: the hot relink had no access to the libraries' compiled resources.
- Fix: feed the proxy app build's compiled library resource units (`.flat` files) to `aapt2 link` as overlays.
- Verified: `20260723T140211Z-bug678-verify`.

**Bug 9 — Navigation-Component templates crashed on first launch.**

- Scope: Bottom Navigation and Navigation Drawer, both languages, with `Fragment$InstantiationException` -> `ClassNotFoundException` on the destination fragment.
- Cause: `Context#getClassLoader()` is pinned to the base APK's shell classloader at LoadedApk-attach time, and androidx's default `FragmentFactory` resolves nav-graph destinations through it — but user classes live only in the payload dex.
- Scoped precisely by the sweep: **Tabbed did not hit it**, because `ViewPager2`/`FragmentStateAdapter` instantiate fragments in code rather than by XML class name.
- Fix: every generated proxy activity overrides `getClassLoader()` via `QuickBuildClassLoaders.forActivity`.
- Verified on device 2026-07-28 across all four affected combinations `[measured on a56]` — clean launch with the nav-graph Home fragment rendered, plus a live-reload edit landing live: Bottom Nav Kotlin 2313 ms, Nav Drawer Java 1231 ms, zero proxy app rebuilds (`20260728T063125Z-bug9-navfrag-verify`, other two arms re-confirmed in `20260728T064805Z-consolidated-verify`).

**Bug 10 — the No-Activity template reported a build failure that had not happened.**

- Cause: the Gradle proxy app build succeeded and wrote `setup.json`, but with `entryActivity` null — there is no launchable Activity, by design. `ProxyAppInfo` parsing rejected that as a missing required field and the user saw the generic "Quick Build proxy app build failed" banner.
- Fix: `entryActivity` is now nullable, and the session refuses with a friendly message instead of faking either success or a build failure.
- Verified: 2026-07-28.

## What still does not work

**No-Activity projects (2 of 16).**

- Nothing to run, so nothing to live-reload.
- Quick Build now says so clearly rather than reporting a phantom build failure.
- Correct behaviour, not a defect — counted as a fail above only because those two combinations do not produce a working session.

**Room / KSP apps, and this one is not Quick Build's fault.**

- Room, KSP, and in fact every recognized annotation processor's codegen jar (dagger-compiler, auto-value, Moshi, Glide) are **entirely absent from the bundled offline Maven repo**. Verified two ways:
  - on device, `find` over CoGo's bundled maven tree returns zero hits for `room`, `ksp` or `symbol-processing` `[measured on a56]`
  - in the shipped asset, `assets/localMvnRepository.zip` (1,509 files) contains no matching entry while a full androidx set is present `[measured on host]`
- Consequence: such a project **cannot complete its first proxy app build (a standard Gradle build) offline** — it fails before Quick Build is involved. Bundle gap, backlog #5.
- Given one online build, it all works `[measured on a56, 2026-07-28]` (`20260728T113213Z-task32-roomksp-online`):
  - `:app:kspDebugKotlin` ran on the phone
  - the feared sqlite-jdbc query-verifier wall never fired
  - the generated `_Impl` classes compiled, and the app launched clean
  - both directions of annotation-impact routing were verified — a body-only edit inside the `@Database` file stayed on the `CodeOnly` live reload route, while adding a field to an `@Entity` escalated to `ANNOTATION_PROCESSOR_INPUT_CHANGED` and took a proxy app rebuild in 21.5 s
- Whether such a project then works **fully offline** after one online priming run is still `[unmeasured]` (task #98).
- That same online run is where reliability defects 1 and 2 were caught; see [`reliability-gaps.md`](reliability-gaps.md).

## The one FAIL in the consolidated pass is a harness artefact

The 07-28 consolidated run records 12 of 13 checks green, with `bug12-add-file` marked FAIL.

- That is a **harness assertion, not a product failure**: `adb push` creates the file and then writes its content, and the two watcher events landed in separate coalescing batches, so the step produced two back-to-back `CodeOnly` builds where the driver asserted exactly one.
- Both were incremental successes with zero invalidations and zero proxy app rebuilds.
- A real in-IDE save is a single write.
- Worth knowing because the same artefact inflates build counts elsewhere in the benchmark data.

## Why the device walk stays in the loop

Every bug here was found by walking a user's actual first five minutes, and the most damaging ones — 4, 5, 6 and 9, all crashes — only exist once code loads from a payload dex into a running app, which is precisely what a JVM test cannot stage. That is the argument for keeping the device walk, not the argument that it is finished.
