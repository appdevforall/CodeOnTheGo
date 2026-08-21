# How resource updates are handled

What happens when the user saves a resource file while Quick Build is running, who consumes each result, and the design decisions around it. Terms (proxy app, payload, rebaseline, orchestrator) are defined in the [README](../README.md); the reload steps are in [pipeline.md](pipeline.md). Behavior verified on-device 2026-08-13 UTC (A56, CoGo C-d-0812-1737).

## Two independent pipelines fire on a resource save

Saving a resource file (a values file, a layout, a drawable) starts two pipelines that never wait on each other:

1. **Quick Build's reload pipeline.** The project watcher picks up the write (inotify plus a

  2 s mtime poll), the coalescer batches it (150 ms quiet window), and the daemon relinks resources with aapt2 using `--stable-ids`, packages a payload, and deploys it to the running proxy app. New files, new values, and new resource ids all reach the running app this way in seconds. No Gradle.
2. **The IDE's editor-freshness build.** The editor's save path sets `resourceXmlSaved`

  (`SaveResultFlags.kt`) and calls `ProjectManagerImpl.generateSources()` from two call sites (`SaveFileAction`, `EditorHandlerActivity`), which runs a Gradle build of the source-generation tasks for each Android module's selected variant - resource generation, source generation, resource processing, plus the viewBinding base-classes task when viewBinding is enabled. 5 to 16 s on an A56-class device. Its job is regenerating the intermediates R.jar the language servers read. The path predates Quick Build (`3f7db1771`, 2022-05-03 - Gradle was the only way to surface a new `R.string.foo` to the editor at the time). Previously it fired for any XML save; it now fires only for resource files (see Design decisions).

Quick Build never consumes the Gradle build's output. The cost of pipeline 2 is contention: CPU against the reload, and the single Gradle slot against the user's own sync or run.

## Who consumes what

| Consumer                  | Where its R symbols come from                                | Needs the Gradle build?                                      |
| ------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| The running app           | Quick Build's aapt2 relink. A new id declared and referenced in XML resolves inside the same relink that allocates it (verified in manual QA: new `res_label` string referenced from a layout - see manual-qa.md) | No                                                           |
| Java language server      | The intermediates R.jar. A successful `generateSources` posts the same `ProjectInitializedEvent` a sync posts, and `JavaLanguageServer.setupWithProject` clears its R.jar cache on it (verified: a new id resolves in a `.java` file with no sync) | Yes                                                          |
| Kotlin language server    | Nothing after first init. The sync-update listener body is `= Unit` and no code path re-reads a regenerated R.jar; only an IDE restart refreshes it (verified: a new id stays flagged before and after a sync) | No - the build has no effect. Pre-existing CoGo bug, ticketed separately |
| Quick Build's hot compile | A payload R.jar copied at provisioning. A new resource id referenced from code mid-session may not compile until a rebaseline | Unverified - open item below                                 |

## Design decisions

**Keep the Gradle build, but only for resource files.** The build is what keeps the Java editor's `R.*` resolution current, so it cannot be removed. The trigger is `resourceXmlSaved` (`SaveResultFlags.kt`), which narrows the old any-XML condition using `ProjectManagerImpl.isAndroidResource`: a prefix match of the file path against the Gradle model's actual resource directories (`sourceProvider.resDirs`, plus dependent modules'), so custom `res.srcDirs` are covered - it does not match on a folder named `res`.

**Defer the build while a Quick Build session is live.** Implemented on this branch: `GenerateSourcesDeferral` (app module), attached to the session-state flow. Since Quick Build never reads the build's output, running it after the reload finishes only delays editor symbol freshness by a few seconds and removes the CPU contention. The mechanism is a coalescing queue, not a save-time status check: at save time the Quick Build pipeline has not started yet (the watcher batch is still inside its 150 ms debounce), so sampling "is Quick Build building?" at that moment misses the primary case. Instead: while a session is active, park the request and run one coalesced `generateSources()` when the orchestrator goes idle; with no session, run immediately as today. This also fixes a silent drop - `generateSources` bails when a build is already running (`ProjectManagerImpl.kt`, the `isBuildInProgress` early return), which swallowed 12 of 18 requests in the manual QA pass (manual-qa.md). Parking alone does not fix it, because session state cannot see a Gradle build the session did not start: a project sync or the user's own Run holds the same single slot while the session reads as settled, so the release fires into a refusal. `generateSources` therefore reports whether it dispatched, and a refused request stays parked and retries on the same grace window rather than being cleared - bounded, so a durable refusal (no build service, tooling server down) gives up instead of burning timers.

**Not chosen: skipping the build when no symbol changed.** Diffing the saved file's declared symbol set (names in values files, `@+id` in layouts; other files' symbol is the filename) costs single-digit milliseconds, but needs a per-file symbol cache with seeding and delete/rename handling. With the deferral in place the build no longer competes with the reload, so this is a followup, not a requirement.

## Open items

- **Probe: does a new resource referenced from code compile mid-session?** The hot compile

  resolves `R.*` from the payload R.jar snapshotted at provisioning, which suggests it fails until rebaseline - but this has not been observed. Manual QA only covered the XML-reference case, which works.
- **Kotlin's frozen jar view.** Separate CoGo bug, independent of Quick Build; no Gradle run

  helps until the Kotlin LSP re-reads jars. Ticket drafted with repro.
- **Alternative: Quick Build emits its own R at aapt2 link time.** Would let code reference a

  new resource mid-session without Gradle. Feasible per AGP 8.8.2 source (AGP generates R bytecode with ASM from aapt2's text symbol table; ids are inlined constants, so `--emit-ids` fed forward as the next `--stable-ids` is mandatory), but the daemon's `IncrementalCompiler` snapshots its classpath once per session, so a fresh R jar means a compiler rebuild (~2.7 s full app-module recompile on mybasic/A56) per new-resource edit. Does not help the editor either way - the language servers read the Gradle-owned R.jar. Gated on the probe above plus a rebuild-cost measurement on target hardware.
