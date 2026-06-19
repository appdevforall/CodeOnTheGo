# Code Review Guide

How to give a good review on Code On The Go. This is a coaching doc, not a gate — use judgment, explain the *why*, and prefer a concrete suggestion (or a diff) over "this is wrong." It complements, and does not replace, [ARCHITECTURE.md](ARCHITECTURE.md) (the source of truth for patterns) and the operational rules in `AGENTS.md` / `CLAUDE.md`.

## How to use this

- **Author:** self-review against this list *before* requesting review. Most of it you can check in five minutes.
- **Reviewer:** you own correctness, leaks, security, and tests. Don't rubber-stamp; don't bikeshed style the formatter already enforces (`ktfmt` / `google-java-format`, 2-space indents).
- Tie every blocking comment to a concrete risk (a crash, a leak, a CVE class, an untested branch). Tag non-blocking polish as **nit:** so the author can triage.

## The 60-second checklist

- [ ] **Exceptions** are handled locally — nothing unexpected reaches the global Sentry crash handler.
- [ ] **No leaks** LeakCanary would catch later: every register/open/subscribe has a matching unregister/close in the right lifecycle callback.
- [ ] **No main-thread disk/network I/O** — no new StrictMode violations, and no whitelisting of *our own* code.
- [ ] **Security:** untrusted input (zip entries, URLs, file paths, web-server requests) is validated; no secrets in code, logs, or analytics.
- [ ] **Tests:** non-UI logic has unit coverage. Where coverage is thin, there's logging to diagnose it in the field.
- [ ] **No duplication:** the change reuses existing helpers instead of copy-pasting.
- [ ] **Docs:** public classes/functions have KDoc/Javadoc explaining *why*, not *what*; any module `README`/`ARCHITECTURE.md`/ADR the change affects is updated in the same PR.
- [ ] **Strings** are in `strings.xml`, not inline literals.
- [ ] **Accessibility:** every actionable view has a `contentDescription` (XML *or* programmatic); decorative views are marked `importantForAccessibility="no"`.
- [ ] **Contextual help:** new interactive elements (and any new screen/panel) have long-press help wired to the 3-tier tooltip system.
- [ ] **Analytics:** meaningful user/build actions emit an event (see below).
- [ ] **Scope/size:** PR is focused and within the ~500 LOC / 10-file guideline (`AGENTS.md`).

---

## 1. Exception handling — stay out of the Sentry crash wrapper

`IDEApplication` installs a global uncaught-exception handler (`handleUncaughtException`) that reports to **Sentry** and then runs the device/credential-protected loaders' handlers. An exception that escapes your code lands there and is recorded as a **crash**. That handler is a safety net, not a control-flow tool.

- **Catch where you can recover.** Wrap I/O, parsing, IPC to the `tooling-api`, git, and plugin calls. Convert failures into a sealed error state (`…UiEffect.ShowError`, `Result`, `BuildState.Failed`) the UI can render.
- **Never swallow silently.** A bare `catch (e: Exception) {}` hides bugs. At minimum log it; if it's notable-but-handled, report it explicitly with the established idiom:
  ```kotlin
  } catch (e: IOException) {
      log.error("Failed to install APK for {}", projectName, e)
      Sentry.captureException(e)   // handled, but we want visibility
      _uiState.update { it.copy(error = e.toUserMessage()) }
  }
  ```
- **Catch narrowly.** Prefer specific types over `Throwable`/`Exception`. Don't catch `CancellationException` in coroutines — rethrow it so structured cancellation works.
- **Coroutines:** an uncaught exception in a `launch` propagates to the scope's handler. Handle it inside the coroutine; don't rely on the crash wrapper to mop up.

## 2. Memory & resource leaks — fix them before LeakCanary does

LeakCanary runs in debug builds (`debugImplementation`), so leaks *will* surface — catch them in review first. Common offenders here:

- **EventBus:** every `EventBus.getDefault().register(this)` needs an `unregister(this)` in the symmetric lifecycle callback (`onStart`/`onStop`, `onResume`/`onPause`). Missing unregister = leak **and** stale-event bugs.
- **Context:** don't hold an `Activity`/`Fragment`/`View` context in a `single` (Koin), a long-lived object, or a companion. Use `applicationContext` for anything outliving a screen.
- **Listeners/receivers/observers:** `BroadcastReceiver`, `ServiceConnection`, `ContentObserver`, editor/terminal callbacks — unregister them. We have real examples (`AppLogsCoordinator`, the log service connection); follow that lifecycle.
- **Closeables:** files, cursors, streams, the `tooling-api` connection — use `use {}` or close in `finally`.
- **Coroutines:** launch in `viewModelScope` / `lifecycleScope`, never `GlobalScope`. A scope tied to a destroyed component must be cancelled.
- **Bitmaps/large buffers** (APK viewer, image-to-XML, previews): release them; don't cache unbounded.

## 3. Threading & StrictMode — don't whitelist our own sins

The app runs a real StrictMode policy via `StrictModeManager` with a **whitelist engine**. Project rule (see learnings/memory): **the whitelist is only for vendor/framework code we can't change — never for app-owned violations.** If your code trips StrictMode, fix the code.

- Move disk and network I/O off the main thread (`Dispatchers.IO`, `withContext`). No DB reads, file reads, or `SharedPreferences` first-access on the UI thread.
- Don't reach for `allowThreadDiskReads()` / `permitAll()` to silence a violation in our code.
- Touch UI only on the main thread; post results back via `StateFlow`/`withContext(Dispatchers.Main)`.

## 4. Security — OWASP Top Ten for a mobile IDE

This app extracts archives, runs a local web server, stores git credentials and signing keys, and executes builds — so the attack surface is real. Review accordingly:

- **Injection / path traversal (Zip Slip):** template/project extraction (`ZipRecipeExecutor`) and any unzip must reject entries that resolve outside the target dir (`canonicalPath.startsWith(targetDir)`). Validate file paths built from user/project input.
- **SQL:** use parameterized queries (`rawQuery(sql, args)` with `?` placeholders), never string-concatenated SQL. (Existing `WebServer`/tooltip queries already do this — match them.)
- **Secrets & credential storage:** git tokens, keystore/signing passwords → `EncryptedSharedPreferences` / the Android Keystore, never plaintext files, never committed, **never logged or sent to analytics/Sentry**. Scrub secrets from breadcrumbs and exception messages.
- **Local web server (`WebServer`):** bind to loopback, scope what it serves, and don't reflect unsanitized input into responses. Treat every request as untrusted.
- **Network:** HTTPS only; no disabled TLS/hostname verification; verify git remotes.
- **Untrusted code/plugins:** respect the `plugin.json` permission model; don't widen plugin capabilities or load classes from untrusted sources without the manager's checks.
- **Deserialization & intents:** validate parsed JSON (Gson/kotlinx) and incoming `Intent`/deep-link extras; don't trust their shape.
- **Logging leaks:** PII, file contents, tokens, and full request bodies don't belong in logs.

## 5. Tests & coverage

**If the code is not purely UI, expect unit tests in the same PR.** ViewModels, repositories, parsers, builder/tooling logic, and security-sensitive helpers are all testable off-device.

- Test the *behavior*: drive `onEvent(...)` / methods against a fake or MockK'd repository and assert the emitted `UiState` sequence (and effects via the effect `SharedFlow`). See ARCHITECTURE.md → Testing.
- Tools: **JUnit (Jupiter)**, **Truth** assertions, **MockK** (new) / Mockito-Kotlin (legacy), **Robolectric** for framework-dependent JVM tests. Live in the module or the shared `testing:*` harnesses.
- Cover the **error and edge paths**, not just the happy path — those are exactly what the crash wrapper would otherwise catch in production.
- Pure-UI changes (layout, view wiring) may legitimately have no unit test; say so in the PR description so the reviewer isn't guessing.

### When coverage is unavoidably low, add logging
Some code is genuinely hard to unit-test (IPC with the `tooling-api`, native/llama boundaries, terminal/PTY, real device I/O). When you can't test a non-trivial branch, **instrument it** so it's diagnosable in the field — log decision points and failure modes via SLF4J:
```kotlin
private val log = LoggerFactory.getLogger(MyClass::class.java)
...
log.info("Selected build strategy {} for project {}", strategy, projectName)
log.warn("Falling back to {} after {}", fallback, reason)
```
Logging is a **stopgap that buys observability, not a substitute for a test.** Prefer a test where one is feasible.

## 6. Observability — logging & analytics

**Logging:** use SLF4J (`LoggerFactory.getLogger(Class::class.java)`), not `android.util.Log`. Right level (`debug` for flow, `info` for milestones, `warn`/`error` for problems), structured `{}` placeholders, and pass the throwable as the last arg (don't `"$e"`). No secrets/PII (see §4).

**Firebase Analytics:** events go through the injected `IAnalyticsManager` (Koin `single<IAnalyticsManager>`), not `FirebaseAnalytics` directly. When a PR adds a meaningful user or build action, ask whether it deserves an event. Good candidate locations:

| Where | Call |
|---|---|
| Screen/fragment shown | `trackScreenView(name)` |
| Project created / opened / cloned | `trackProjectOpened(path)` |
| A feature is actually used (editor action, layout designer, plugin install, AI agent run) | `trackFeatureUsed(name)` |
| Build started / completed / strategy chosen | `trackBuildRun(...)`, `trackBuildCompleted(...)`, `trackGradleStrategySelected(...)` |
| Recoverable, notable error | `trackError(type, message)` |

Keep event names/params stable and low-cardinality; **no PII, file paths with usernames, or secrets** in event payloads.

## 7. Code quality

- **No duplication.** If you copy-pasted a block, extract a function/extension into the right `common`/`utils` module. Before adding a helper, grep — we likely already have it. Repeated literals/magic numbers → named constants.
- **Docstrings.** Public classes, functions, and non-obvious logic get KDoc/Javadoc. Document the *contract and the why* (threading expectations, nullability, side effects, units), not a restatement of the signature.
- **Keep docs in sync with the code.** If a change alters a module's public API, commands, or behavior that's described in its `README.md` (or `ARCHITECTURE.md` / an ADR), update that doc in the *same* PR. A doc that documents a non-existent API is worse than no doc. If the doc-fix is genuinely out of scope, leave a tracked note (a ticket) rather than silently letting it drift.
- **Strings in `strings.xml`.** User-facing text must be a string resource, never an inline literal — lint flags `HardcodedText`, and externalized strings feed our Crowdin translation flow. Use plurals/`getQuantityString` and positional args for formatting. Log messages and analytics keys are *not* user-facing and stay in code.
- **Dependencies:** don't add one without checking `gradle/libs.versions.toml` first — we probably already have it (`AGENTS.md`).

## 8. Accessibility — every actionable view speaks

CoGo serves visually-impaired developers, so TalkBack support is a correctness requirement, not a nice-to-have (pattern set by ADFA-2667). New UI is Compose ([§10](#10-architecture-alignment) / [ADR 0009](docs/adr/0009-jetpack-compose-for-new-ui.md)), so each rule gives the View and Compose form — the requirement is the same in either.

- **Label every actionable view.** Buttons, `ImageButton`s, and icon-only controls need a `contentDescription`.
  - *View:* `android:contentDescription="@string/cd_…"`; an unlabeled icon button reads as "unlabeled button".
  - *Compose:* pass `contentDescription` on `Icon`/`Image`; for a custom clickable or `IconButton` (whose inner `Icon` is usually `null`), label the control via `Modifier.semantics { contentDescription = … }`.
- **Don't forget elements built in code.**
  - *View:* the miss is anything *not* in XML — toolbar actions, RecyclerView rows, inflated buttons. Set `contentDescription` when you create them (see `EditorHandlerActivity.getToolbarContentDescription()`, `MainActionsListAdapter`, `DiagnosticItemAdapter`).
  - *Compose:* `Icon`/`Image` force a `contentDescription`, so the miss is a vague label — or a lazy `null` on something actionable.
- **Silence decoration.** Separators, background images, an icon beside a label that already says the same thing — skip them, don't read them as noise.
  - *View:* `android:importantForAccessibility="no"` (or `View.IMPORTANT_FOR_ACCESSIBILITY_NO`).
  - *Compose:* `contentDescription = null` on the decorative `Icon`/`Image`, or `Modifier.clearAndSetSemantics { }` to drop a subtree.
- **Describe the action, not the picture.** `cd_sync_project`, not "circular arrows icon". For toggles, make it state-aware — `cd_drawer_open` vs. `cd_drawer_close` — not one ambiguous label. (In Compose, pull text with `stringResource(R.string.cd_…)`.)
- **Externalize, with the `cd_` convention.** Content descriptions live in `strings.xml` as `cd_*` — greppable, translatable, reusable; check for an existing one first. `HardcodedText` lint does **not** catch Compose literals, so reviewers must.
- **Bonus — it stabilizes tests.** Screen-reader semantics are what UI tests match on (`ACTION_CLICK` for Views, `onNodeWithContentDescription(…)` for Compose), so a11y and reliable instrumentation tests are the same work.

## 9. Contextual help — long-press works everywhere

Help in CoGo is reached by **long-press**, anywhere: a progressive three-tier experience — **Tiers 1 & 2 are tooltips** (anchored popups from `idetooltips`), **Tier 3 is a full help web page** via the tooltip's "See More" link. A long-press should never be met with silence.

- **Wire up help on new interactive elements.** Anything tappable — buttons, icon controls, menu items, list rows, toolbar actions — gets long-press help. A new actionable view with no tooltip is as incomplete as a missing `contentDescription`.
- **Cover new screens and panels too.** Even where pixels aren't interactive, a new screen/panel/dialog needs a top-level help entry so help is always reachable.
- **The affordance is the requirement, not finished copy.** Tooltip content may still be in authoring — fine — but the long-press must be wired and routed into the tier system. Don't ship UI that can never surface help.
- **Reuse the system.** Wire help through `idetooltips` — today the `View.displayTooltipOnLongPress(context, anchorView, category, tag)` extension (`setOnLongClickListener` → `TooltipManager.showTooltip`) — not a one-off popup.
- **Compose has no native entry point yet** (tracked by **ADFA-4381**). The helper is View-based (needs an `anchorView`), so until `idetooltips` grows a Compose API, a composable wires help via `AndroidView` interop. Flag it in review rather than skipping help, and build the reusable `Modifier`/wrapper once instead of copy-pasting interop.

## 10. Architecture alignment

Hold the change to the patterns in [ARCHITECTURE.md](ARCHITECTURE.md):
- **New UI is Jetpack Compose** ([ADR 0009](docs/adr/0009-jetpack-compose-for-new-ui.md)) — a new XML layout / `Fragment`-rendered screen for the IDE's own UI should be sent back. Compose changes only the view layer; the rest of this list still applies. (Existing XML screens are fine until reworked.)
- New screens follow **UDF**: `ViewModel` + `StateFlow<UiState>`, sealed `UiEvent`/`UiEffect`, repository for data. Composables collect state via `collectAsState()`; keep I/O and business logic out of composables/Activities.
- DI via **Koin** (constructor injection); register new singletons/viewModels in the module.
- **Persistence:** raw SQLite or filesystem — **not Room** (Recent Projects is the lone legacy exception).
- **UI safety:** never place our UI over the two Android system bars — the top status bar and the bottom navigation bar (`AGENTS.md`).

## 11. Offline-first

CoGo is meant to work **without a network** — editing, building, and running an app on-device must not depend on connectivity. Hold new work to that:

- **Degrade gracefully offline.** A feature that needs the network must still launch, explain itself, and leave the rest of the app usable when there's no connection — never block a core flow (edit/build/run) on a request.
- **Network calls are non-blocking and failure-tolerant.** Analytics, Sentry, and Gemini calls run off the main thread and must tolerate timeouts/failures silently (no crash, no hang, no lost user action). A dropped analytics event is acceptable; a dropped keystroke is not.
- **No network on the critical path.** Don't introduce a connectivity dependency into startup, the editor, or the build pipeline.

## 12. PR hygiene

- Focused and reviewable: aim for the **~500 LOC / 10-file** ceiling; split larger work into stacked PRs.
- Title/branch follow `ADFA-####`; description says *what changed, why, how it was verified*, and flags anything intentionally out of scope (e.g. "UI-only, no unit test").
- No stray debug logs, commented-out code, `dummy.apk`-style artifacts, or unrelated reformatting that buries the real diff.

---

## Open for discussion (proposed additions)

These aren't established rules yet — flagging them as candidates for the team:

- **Backward compatibility:** `MIN_SDK=28` — review new APIs for guard/desugaring; remember user-built apps target `MIN_SDK_FOR_APPS_BUILT_WITH_COGO=16`.
- **Performance budget for startup & editor:** watch added work in `Application.onCreate`, `tooling-api` startup, and per-keystroke editor paths; flag synchronous heavy work there.
- **Feature flags / kill switches** for risky surfaces (AI agent, plugins, web server) so we can disable in the field without a release.

Add to or push back on any of these — this doc is meant to evolve with the team.
