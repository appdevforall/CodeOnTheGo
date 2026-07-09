# Code-review response (ADFA-4128 Mini-Stubby spike)

Disposition of the 18 findings from the review pass. The reviewer's central point —
the tier system could **report success while silently losing the edit** (the worst
failure mode for an edit→run loop) — drove the priority: every edit-loss path is now
fixed or gated. Verified on-device after the fixes: a Main-body-only edit takes Tier 1
(hot-swap); adding a method correctly falls back to Tier 2; no crash.

## Fixed

| # | Finding | Fix |
|---|---|---|
| 4 | Tier 1 dexed only `Main.class` but a new lambda/SAM sibling or edited helper class would no-op-succeed or `NoClassDefFoundError`-crash | **Class-set digest gate** (`onlyMainChanged`): Tier 1 runs only when the *sole* change is `Main.class`'s bytes — no class added/removed, no other class changed. Anything else → Tier 2. |
| 3 | Mixed src+res save dropped the resource half (used cached `resources.apk`) | Dispatch now `linkResources()` on a mixed save AND forces Tier 2 (Tier 1 can't carry resources). |
| 1 | Tier 1 success didn't refresh deployed artifacts → a later Tier 0 (reuses `appDexDir`) or restart reverted the edit | After a successful redefine, converge the Mac-side caches (full `d8` → `appDexDir`, repackage `outApk`) so Tier 0 ships current code. *(On-device `payload.apk` is intentionally not re-pushed — that would trigger a redundant reload; a shell **restart** reloads the last full deploy: documented limitation, see Deferred.)* |
| 2 | `ok>0` "any-gen success" could report success while the visible gen's redefine failed | Now safe by construction of #4: Tier 1 only sends body-only changes, so the current (visible) gen's schema always matches the dex and it is always among the successes; a stale gen's failure is harmless. Comment added in `do_redefine`. |
| 7 | `sh()` never checked exit codes → a failed `rm result` left a stale `result` → false Tier-1 success | **Nonce protocol**: the agent echoes the request nonce in `result`; the service accepts only its own nonce. Staging commands now use `shOk()` (exit-code checked) and fail fast to Tier 2. |
| 6 | JNI local-ref leak: `GetLoadedClasses` refs never released on the long-lived watch thread → eventual "local reference table overflow" | `PushLocalFrame`/`PopLocalFrame` around the redefine loop. |
| 5 | Agent re-attached on every `onCreate` (rotation) → multiple watch threads racing the trigger | Once-per-process guard in the shell (`sAgentAttached`) **and** a static guard in `Agent_OnAttach` (belt-and-suspenders). |
| 9 | kotlinc diagnostics discarded → "COMPILATION_ERROR" with no file/line | Capture the compiler's output (`lastDiag`) and log it on failure. |
| 10 | Failure rollback restored classloader + resources but NOT the theme | Snapshot `previousThemeRes`; restore + re-`applyThemeTo` in the catch. |
| 11 | `deleteClassFilesUnder` kept every `R*` class (incl. user `RowView`/`Repo`) → stale classes | Match `R.class` / `R$*.class` exactly. |
| 12 | `extractNativeLibs` zip-slip + one odd entry aborted ALL native libs | Canonical-path containment guard; `mkdirs()` for subdir entries; skip one bad entry instead of aborting the loop. |
| 14 | `nativelib-genN` dirs never trimmed (unbounded codeCache growth) | Trim them with the same keep-2 policy as the gen apks. |
| 15 | Tier-1 temp trigger file never deleted (one orphan per attempt) | `Files.deleteIfExists(trg)` in a `finally`. |
| 17 | `read_file` didn't check `fseek`/`ftell` → `malloc(0)`+`fread(-1)` heap corruption on error | Guard `fseek`/`ftell` return; bail on `n < 0`. |

## Deferred (documented; spike-acceptable, with the fix noted)

| # | Finding | Why deferred / production fix |
|---|---|---|
| 8 | A `ProxyActivity` alive across a reload mixes generations (its Resources keep the old loader while its factory reads the new classloader) | Multi-screen + live-reload-mid-navigation is a narrow case in the spike. Production fix: keep a registry of live `ProxyActivity`s and `finish()`/recreate them on reload (same same-gen invariant MainActivity enforces). |
| 13 | The reload (copy + two zip scans + `getPackageArchiveInfo`) runs on the main thread → jank/ANR on a realistic 10–50 MB app | Fine at the current ~100 KB–1.4 MB payloads. Production fix: do copy/extract/scan on a background thread, hop to main only for the loader swap + render. |
| 1b | On-device `payload.apk` isn't refreshed after Tier 1, so a shell **process restart** reloads the last full deploy (pre-Tier-1 code) | Rare in a dev loop, and the code is one edit away. Production fix: write `payload.apk` with a reload-suppression sentinel the shell honors, or a separate non-watched "restart baseline" apk. |
| 14b | Removed `ResourcesProvider`s (each dup'd an fd) aren't `close()`d — fds held until GC | GC reclaims them; only a very long session pressures fds. Production fix: close the removed loader's providers once no Activity references the old gen. |
| 16 | Shell-out strings are concatenated, not arg-vector'd (breaks on spaces; injection-shaped) | Not exploitable as written — package name and spike paths are fixed constants. Production fix: `ProcessBuilder` arg lists throughout; stage the redefine dex under a run-as-owned tmp name instead of world-readable `/data/local/tmp`. |
| 18 | `adb reverse` binds device-side loopback reachable by any co-installed app → it could POST prompts to the orchestrator's `/ask` (prompt-injection into the Mac-side Claude) | Acceptable on the dedicated A56. Before this pattern nears CoGo: a shared-secret header on `/ask`, and don't expose the orchestrator via `adb reverse` in shared environments. |

## Verified non-issues (recorded by the reviewer)

Atomic deploy (tmp+`mv`) fires FileObserver exactly once; read-only stale-gen delete works
(dir perm, not file perm); deleting a gen apk under a live loader is safe (open fds);
the `PayloadViewFactory` gen-cache "race" is unreachable (reload + inflation both on the
main thread); the trigger dir is app-private `0700`.

## Overall

The hot-reload spine (copy→read-only codeCache → ResourcesLoader swap → DexClassLoader swap
→ render, with classloader+resource+theme rollback and the generation-aware factory) is
sound. The tier system's state-coherence gaps the reviewer flagged — the four ways Tier 1
could lose an edit while reporting success — are the important fixes and are now closed
(class-set gate, mixed-save relink, cache convergence, nonce). The remaining deferred items
are performance (main-thread reload), narrow-case (proxy cross-gen), or hardening (arg
vectors, loopback auth) appropriate to leave for the production pass, each with its fix noted.
