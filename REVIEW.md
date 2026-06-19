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
- [ ] **Docs:** public classes/functions have KDoc/Javadoc explaining *why*, not *what*.
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
- **Strings in `strings.xml`.** User-facing text must be a string resource, never an inline literal — lint flags `HardcodedText`, and externalized strings feed our Crowdin translation flow. Use plurals/`getQuantityString` and positional args for formatting. Log messages and analytics keys are *not* user-facing and stay in code.
- **Dependencies:** don't add one without checking `gradle/libs.versions.toml` first — we probably already have it (`AGENTS.md`).

## 8. Accessibility — every actionable view speaks

CoGo serves visually-impaired developers, so screen-reader support (TalkBack) is a correctness requirement for UI work, not a nice-to-have. The pattern below is what ADFA-2667 established — hold new UI to it.

- **Label every actionable view.** Buttons, `ImageButton`s, and icon-only controls need a `contentDescription`. In layouts: `android:contentDescription="@string/cd_…"`. An unlabeled icon button is announced as "unlabeled button" — useless.
- **Don't forget views built in code.** The easy miss is anything *not* in XML — toolbar action items, RecyclerView rows, dynamically-inflated buttons. Set `contentDescription` programmatically when you create them (see `EditorHandlerActivity.getToolbarContentDescription()`, `MainActionsListAdapter`, `DiagnosticItemAdapter` from ADFA-2667).
- **Silence decoration.** Purely visual views — separators, background images, an icon sitting next to a label that already says the same thing — get `android:importantForAccessibility="no"` (or `View.IMPORTANT_FOR_ACCESSIBILITY_NO`) so TalkBack skips them instead of reading noise.
- **Describe the action, not the picture.** Prefer what tapping *does* over what the icon *looks like*: `cd_sync_project`, not "circular arrows icon". When a control toggles state, make the description state-aware — `cd_drawer_open` vs. `cd_drawer_close`, expand vs. collapse — rather than one ambiguous label.
- **Externalize, with the `cd_` convention.** Content-description strings live in `strings.xml` named `cd_*` (so they're greppable, translatable via Crowdin, and not inline literals). Reuse an existing `cd_` string before adding a near-duplicate.
- **Bonus: it stabilizes tests.** Our UI tests drive views through accessibility actions (`ACTION_CLICK`), which need these labels — so good a11y and reliable instrumentation tests are the same work.

## 9. Contextual help — long-press works everywhere

Help in CoGo is reached by **long-press**, anywhere, and opens a progressive three-tier experience: **Tier 1 & 2 are tooltips** (anchored popups from `idetooltips`, via `showIDETooltip`'s `level` argument), and **Tier 3 is a full help web page** reached from the tooltip's "See More" link. This is a product promise — a long-press should never be met with silence. See [`idetooltips/README.md`](idetooltips/README.md) for the system itself.

- **Wire up help on new interactive elements.** Any view that does something when tapped — buttons, icon controls, menu items, list rows, toolbar actions — gets long-press help. A new actionable view with no tooltip affordance is an incomplete change, the same as a missing `contentDescription`.
- **Cover new screens and panels too.** Even where individual pixels aren't interactive, a new screen/panel/dialog needs at least a top-level help entry so help is reachable from anywhere on that surface.
- **The affordance is the requirement, not finished copy.** Tooltip *content* may still be getting authored — that's fine — but the long-press must already be wired and route into the tier system. Don't ship UI that can never surface help.
- **Reuse the system; don't reinvent it.** Use `showIDETooltip` / the `idetooltips` module rather than a one-off popup, so all three tiers and the tooltip store stay consistent.

## 10. Architecture alignment

Hold the change to the patterns in [ARCHITECTURE.md](ARCHITECTURE.md):
- New screens follow **UDF**: `ViewModel` + `StateFlow<UiState>`, sealed `UiEvent`/`UiEffect`, repository for data. Don't put I/O or business logic in Fragments/Activities.
- DI via **Koin** (constructor injection); register new singletons/viewModels in the module.
- **Persistence:** raw SQLite or filesystem — **not Room** (Recent Projects is the lone legacy exception).
- **UI safety:** never place our UI over the two Android system bars — the top status bar and the bottom navigation bar (`AGENTS.md`).

## 11. PR hygiene

- Focused and reviewable: aim for the **~500 LOC / 10-file** ceiling; split larger work into stacked PRs.
- Title/branch follow `ADFA-####`; description says *what changed, why, how it was verified*, and flags anything intentionally out of scope (e.g. "UI-only, no unit test").
- No stray debug logs, commented-out code, `dummy.apk`-style artifacts, or unrelated reformatting that buries the real diff.

---

## Open for discussion (proposed additions)

These aren't established rules yet — flagging them as candidates for the team:

- **Backward compatibility:** `MIN_SDK=28` — review new APIs for guard/desugaring; remember user-built apps target `MIN_SDK_FOR_APPS_BUILT_WITH_COGO=16`.
- **Performance budget for startup & editor:** watch added work in `Application.onCreate`, `tooling-api` startup, and per-keystroke editor paths; flag synchronous heavy work there.
- **Offline-first:** CoGo is meant to work without a network. New features should degrade gracefully offline; analytics/Sentry/Gemini calls must be non-blocking and failure-tolerant.
- **Feature flags / kill switches** for risky surfaces (AI agent, plugins, web server) so we can disable in the field without a release.

Add to or push back on any of these — this doc is meant to evolve with the team.
