# Same-app-id mode design contract (Path B)

- **Status:** Contract for implementation — ADFA-4128 follow-on, plan
  `plan-component-proxying.md` Path B. Requires Path A
  (`component-proxying-design.md`) — uniform component proxying is what makes a
  same-id test app honest, per ADR 0010's revisit clause.
- **Date:** 2026-07-20
- **Scope:** an OPT-IN per-project mode that installs the Quick Build test app
  under the project's real `applicationId` instead of the `.quickbuild` suffix.
  Default stays the suffix (ADR 0010 unchanged as the default posture; its
  Alternatives entry moves from "revisit if proxying widens" to "planned as
  opt-in"). Everything remains behind the experiments flag.

What same-id buys: Firebase init, FCM delivery into proxied services, Google
Sign-In/OAuth against the debug cert, verified app links, billing test tracks,
and — the headline — the real app's data directory, preserved across the switch
because a same-signature install is an *update*, not a replace.

## 1. Mode plumbing

**Per-project toggle, CoGo-side.** The mode is a per-project setting stored with
the rest of CoGo's per-project Quick Build state (project preferences, not a
gradle file — the user's project is never edited). The session manager reads it;
`GradleQuickBuildProvisioner` forwards it to the setup build via the existing
`-P` pattern (LogSender/runtime-AAR precedent), with two new
`GradlePluginConfig` constants:

- `cotg.quickbuild.sameAppId=true` — enables the mode for this setup build.
- `cotg.quickbuild.versionCodeOverride=<n>` — the pinned versionCode (section 2).

**Downstream in `QuickBuildPlugin` when `sameAppId` is set:**

- `finalizeDsl` **skips** the `applicationIdSuffix` append. Everything else in
  the variant wiring is untouched.
- `realApplicationId` stays wired as `applicationId.removeSuffix(SUFFIX)`, which
  is now a natural no-op — one code path, no mode branch.
- **Authority rewrite disables itself by construction, not by flag.** Path A's
  rule is anchored on test-app id vs real id: authorities already under the
  test-app id pass verbatim, authorities under the real id are re-prefixed.
  With the ids equal, both cases collapse to verbatim pass-through — every
  authority (including `${applicationId}`-derived ones, which AGP now resolves
  to the real id) is already correct. No transformer change; add a unit test
  pinning the collapse. Authorities embedding neither id keep Path A's
  leave-verbatim/fail-loud behavior (`INSTALL_FAILED_CONFLICTING_PROVIDER`
  cannot occur against the real app — it is being replaced — but can against a
  third app, and stays a loud setup failure).
- **Component map, proxy generation, manifest transform, payload diversion:
  unchanged.** The manifest still swaps proxy names and the runtime's
  `appComponentFactory` under the real id, so OS entry points that target the
  real package (FCM into a service, widget receivers, notification taps,
  app-link activities) instantiate current-generation payload code. This is the
  Path A dependency stated in ADR 0010: without full proxying, the real id buys
  the appearance of parity while side doors break silently; with it, the
  harness genuinely stands in for the app.
- The `<application>` backup neutralization (`allowBackup=false`, backup attrs
  dropped) is **retained** — auto-backup must not snapshot a test harness's
  state as the real app's. A Standard Run restore reinstates the app's own
  backup config.

The eager warm setup build (`warmSetupBuild`) runs with the same properties but
installs nothing, so no clobber can happen before the user confirms (section 3).

## 2. Install orchestration

All of this runs in `provision()` before any install, extending
`InstalledPackages` (add versionCode + signing-cert digest accessors).

**Detection.** Query PackageManager for the real `applicationId`:

- **Not installed:** plain fresh install of the test APK under the real id.
  The clobber warning still shows at mode entry (its consequences apply to
  every later Standard Run switch), minus the "existing app replaced" line.
- **Installed, signature matches:** the preserved-data update path below.
- **Installed, signature differs:** **refuse** (below).

Signature comparison: SHA-256 of the installed package's signing cert
(`signingInfo`) against the cert of the debug keystore CoGo signs with. Apps
previously built and Standard-Run by CoGo on this device match; apps from Play,
sideloads, or another machine's keystore do not.

**Update-install path (the headline).** Same signature means
`ApkInstaller.installApk` proceeds as an *update*: the data directory,
granted permissions, and account state survive. `TestAppInstaller` is reused
unchanged — its byte-identical skip also works here (a rebaseline that rebuilds
identical bytes reinstalls nothing).

**versionCode strategy: `max(installedVersionCode + 1, project versionCode)`,
pinned at mode entry.** CoGo reads the installed real app's versionCode ONCE,
when the user confirms mode entry, computes the override, and passes the same
`-Pcotg.quickbuild.versionCodeOverride` on every setup and rebaseline build for
the whole mode episode (persisted in session state + echoed in `setup.json`).
Pinning prevents the ratchet where each rebaseline re-reads the (now test-app)
versionCode and increments again. `QuickBuildPlugin` applies it via
`variant.outputs[*].versionCode`. The `+1` guarantees the update is never
rejected as a downgrade and makes "the test app is installed" unambiguous in
`dumpsys`/diagnostics.

**Downgrade guard.** If a rebaseline would ever produce a versionCode below the
currently installed one (e.g. the user lowered the project versionCode
mid-episode), the pinned override already floors it — the guard is an assertion
in the provisioner that the override never decreases within an episode, failing
the provision loudly rather than producing an uninstallable APK.

**Signature mismatch: refuse by default.** An update-install would fail
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); the only way forward is uninstall =
**data loss**. v1 refuses mode entry outright with a message naming the reason
("the installed <appId> was not built by this device's CoGo — same-id Quick
Build would have to delete it and its data") and telling the user to back up
and uninstall manually first if they really want the mode. No scarier
second-confirmation path in v1: refusal is simpler, safer, and reversible by
an explicit user action outside Quick Build. Emits `refused` analytics
(section 6).

## 3. Clobber warning UX

Shown on **every mode ENTRY** — toggling the project into same-id mode, and
re-entry after a Standard Run restore ended the episode. Not per deploy, not
per rebaseline, not per session start within an episode. Confirmation is an
explicit destructive-styled dialog (not a snackbar), and nothing — no build, no
install — runs before it is accepted. Consequence list, verbatim intent:

1. The real <appId> on this device is **replaced** by the Quick Build test app
   until a Standard Run reinstalls it.
2. Notifications, shortcuts, widgets, alarms, and push messages for <appId> now
   go to the **test app**.
3. **App data is shared.** The test app reads and writes the real app's data;
   code under active editing can corrupt or migrate that data irreversibly.
4. Every switch between Quick Build and Standard Run is a reinstall, with the
   OS install dialogs (and possibly Play Protect) each time.

Decline = the toggle stays off; nothing was touched. Accept = versionCode is
pinned (section 2) and provisioning proceeds.

## 4. Standard Run restore

In same-id mode, hand-back **is** the restore: the user taps Run, CoGo builds
the project normally and installs the real app under its own id — which
replaces the test app (symmetric clobber; the warning's "until a Standard Run
reinstalls it" line is this, so no second warning here). Wiring:

- The Quick Build session **invalidates before the Standard Run install
  lands**: the baseline's APK is gone, so the session must not deploy another
  payload. Reuse the existing bidirectional hand-back hook (a completed
  Standard build reseeds/invalidates a live session); in same-id mode the
  completed Standard install *ends the mode episode* instead of reseeding —
  next lightning-bolt tap re-enters via the section 3 warning.
- The real app's project versionCode is typically **below** the pinned test
  versionCode, so the restore install is a downgrade. Debuggable packages may
  downgrade via `PackageInstaller.SessionParams.setRequestDowngrade` (API 29+);
  CoGo's Run install path sets it when restoring over a same-id test app. The
  downgrade authority is **persisted per-project** (`restoreDowngradePending`,
  section 6), not held in memory: a restore the user cancels at the OS install
  dialog and retries after a CoGo restart still requests the downgrade, instead of
  failing `INSTALL_FAILED_VERSION_DOWNGRADE` with no recovery. It clears on the next
  mode entry or when the mode is disabled.
- **API 28 restore — NOT YET IMPLEMENTED in v1 (tracked followup).** On the
  API-28 device floor no downgrade API exists, so the restore install fails with
  `INSTALL_FAILED_VERSION_DOWNGRADE`. v1 ships **only the fail-safe half**: the
  install is rejected, nothing is destroyed, and the failure is visible — but
  there is no guided recovery, and the user is left with the test app under the
  real id until they uninstall it manually. The designed recovery — an explicit
  confirmed uninstall (the one place suffix-parity data loss is unavoidable),
  never silent — is a followup, not built here. Its guard hook
  (`SameAppIdGuard.checkUninstall`) exists and is tested but has no caller yet
  (section 7). Do not describe the confirmed-uninstall flow as shipped until it
  is wired.
- The test app's data dir is handed back to the real app as-is — whatever the
  test builds wrote is what the real app now sees (consequence 3 both ways).

A leftover `.quickbuild`-suffixed test app from prior suffix-mode use is
unrelated and stays installed; uninstalling it is offered as cleanup, never
automatic.

## 5. Firebase / google-services under the real id

The setup build must be **transparent to the google-services plugin**: it runs
before/around our tasks and generates resources (`google_app_id`, API keys)
keyed by `applicationId` — which now matches `google-services.json`, so
generation succeeds and the values are the real app's. The Quick Build plugin
must not strip or rename anything Firebase needs:

- `FirebaseInitProvider` (and other library providers) are proxied per Path A
  rules — the class is on the compile classpath, the proxy `extends` it, its
  `${applicationId}`-derived authority resolves to the real id and passes
  verbatim. Init runs in-process exactly as in a normal debug build.
- Firebase/google-services manifest metadata and generated resources transfer
  verbatim (Path A already keeps meta-data); the payload diversion touches only
  project-scope classes, never library code or generated resources.

**What can't work: nothing, by design** — package-bound initialization is the
point of the mode. **What still can't work (user-facing note, not a code
problem):** services that pin a signing certificate, not just a package. If the
user's Firebase/Google Cloud project restricts API keys or OAuth clients to a
release SHA (or a different machine's debug SHA), calls fail with a
cert-mismatch error even under the real id — the fix is registering this
device's CoGo debug SHA in the Firebase console, which only the user can do.
Document in the mode's help text, not as a limitation of the implementation.

## 6. Session / state model

- **Per-project persisted mode state** (`QuickBuildModeStore`): the opt-in toggle,
  the confirmed-clobber flag, the episode's pinned versionCode + real applicationId,
  and `restoreDowngradePending`. All are keyed by project path and survive a CoGo
  restart, so the clobber warning shows once per EPISODE (not per process) and a
  cancelled-then-retried restore keeps requesting its downgrade across a restart
  (section 4). `restoreDowngradePending` is set when a Standard Run ends the episode
  and cleared on the next mode entry or on disable.
- **`setup.json`: additive field, no schema bump** — `"sameAppId": "true"` plus
  `"versionCode": "<pinned>"` written by the report task when the property is
  set. `SetupInfo` gains defaults-false/null fields; old parsers ignore the
  keys (the parser already tolerates unknown fields), old setup.json parses
  with mode off. The `schema` field stays at its current value.
- **Mode toggle = rebaseline boundary.** Flipping the toggle in either
  direction while a session is live stops the session; the next start
  provisions from scratch under the new mode (different package identity =
  nothing from the old baseline is trustworthy). The persisted-payload store is
  fingerprint-keyed to the baseline dex and discards itself on rebaseline
  already — mode flips ride that mechanism.
- **Analytics** (Firebase sink, `quick_build_*` convention, low-cardinality):
  - `quick_build_sameid_entered` — warning accepted, provisioning started
    (params: fresh_install vs update).
  - `quick_build_sameid_clobber_confirmed` — the dialog accept itself (kept
    distinct from `entered` so decline rate is measurable from the gap).
  - `quick_build_sameid_refused` — param `reason`:
    `signature_mismatch | user_declined | version_code_overflow`.
  - `quick_build_sameid_restored` — Standard Run ended the episode (param:
    `downgrade_used` true/false).
  Session-scoped events carry `qb_session_id` as today; `refused` predates a
  session and carries only the project-hash field the other events derive it
  from.

## 7. Safety guard (hard assertions)

One guard class (`domain/`, JVM-tested), consulted by the provisioner
immediately before every install/uninstall call — the last line, independent of
UI flow bugs:

1. **Suffix mode never installs over the real id.** If `sameAppId` is false,
   the target package MUST end with `.quickbuild` AND differ from the project's
   real `applicationId`; otherwise abort the provision with an internal error
   (never-stale-spirit: loud, nothing touched).
2. **Same-id mode never silently uninstalls.** No code path in Quick Build may
   call uninstall on the real id unless it carries a token minted by the
   confirmed section 3 / section 4 (API 28 restore) warning dialog for THIS
   episode. Absent the token, the guard aborts. Install-failure handling must
   surface the failure, never "resolve" it by uninstalling. **v1 wires no
   uninstall path at all** (the API-28 guided restore of section 4 is a
   followup), so `checkUninstall` currently has no caller — it is the tripwire
   that keeps that future path from being added without the token check, and is
   JVM-tested for that reason.
3. **Entry order.** No install under the real id before the clobber warning's
   confirmation token exists (covers the fresh-install case too).

## Test obligations (per README "Verifying changes")

Guard + versionCode pinning + `SetupInfo` additive parsing + the
authority-collapse behavior each get JVM suites (>=90% bar). The plugin's
sameAppId branch gets setup-build tests in `:gradle-plugin` (suffix absent,
versionCode applied, google-services untouched). Device QA walks: mode entry
warning (accept + decline), update-install with data preservation verified
(write data under Standard Run, confirm visible under Quick Build), signature-
mismatch refusal, Standard Run restore incl. the downgrade path, and re-entry
warning after restore. README "Known limitations" gains the cert-pinned-
services note; ADR 0010's Alternatives entry is updated to "planned as opt-in".

## PR followups (need a product decision, not fixed here - 2026-07-21)

- **`ApkSigningCert.sha256` / `getPackageArchiveInfo` can return a null cert on some
  OEMs** (`app/.../QuickBuildInstallAdapters.kt`). A null read is already treated as
  "cannot verify" and routes to the unverified-`Proceed` path (`SameAppIdEntry.decide`'s
  `bothCertsKnown` gate) rather than a hard refusal - so the mode does not currently HARD
  FAIL on this OEM fragility, but it does mean the authoritative provisioner-side check
  (`SameAppIdProvisionGuard.installGate`'s `signatureRefusal`) also sees a null and, per
  its "unreadable counts as a mismatch" rule, refuses the install right before it would
  otherwise proceed - the update-install path silently degrades to "always refuses" on
  those devices. Needs a product call: which OEMs/API levels are affected, whether a
  fallback verification exists (APK signature scheme v2/v3 parsing without
  PackageManager?), and whether "refuse" is really the right default when the read
  itself failed vs. when it succeeded and disagreed.
- **`ProjectHandlerActivity.startSameAppIdEntry` always passes `projectVersionCode =
  null`** to `SameAppIdModeController.requestEntry` (the project model exposes no
  versionCode today). This floors the pinned versionCode to 1 on first entry, which is
  harmless once an app is already installed (the installed versionCode + 1 dominates,
  per `SameAppIdEntry.decide`), but means a FRESH install always pins versionCode 1
  regardless of what the project's own `build.gradle` versionCode says - a later
  Standard Run restore could then need a downgrade immediately if the project's real
  versionCode is higher. Needs a product call on whether to read the real versionCode
  from the Tooling API model (cost/timing of that read against project-open/entry-tap
  latency) or accept the fresh-install floor as permanent behavior.
