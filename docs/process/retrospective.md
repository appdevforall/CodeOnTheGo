# Retrospective Log

## 2026-08-14 - ADFA-5153: shared-dictionary Brotli compression for documentation.db (cross-repo, + docdb-studio fix)

### Time Breakdown

| Started | Phase | 👤 Hands-On Time | 🤖 Agent Time | Problems |
|---------|-------|-----------------|---------------|----------|
| Aug 14 7:48pm | Investigate WebView/dictionary support, discover 3-pipeline fragmentation, iterate schema design | ██▌ 25m | ████████▌ 85m | ⚠ schema redesigned 3x; one ~67min research+background-agent stretch |
| Aug 14 9:40pm | Implement dictionary pipeline (`populate_db.py`, migration script), kick off real-DB migration | █▌ 15m | █ 10m | |
| Aug 14 10:06pm | `WebServer.kt` read-side, parallel testing | █▌ 14m | █▌ 15m | ⚠ 3 fix-rerun cycles (2 real gaps, 1 self-inflicted) |
| Aug 14 10:28pm | Docs, Jira, commits (both repos) | ▌ 7m | ▌ 2m | |
| Aug 14 10:37pm | On-device deploy & verify | █▌ 14m | █ 10m | ⚠ adb/USB dropped 3x; wrong launcher activity first try |
| Aug 14 11:10pm | `docdb-studio` fix, push + PRs, architecture review, retro, parallelize migration script | ▌ 5m | ████████▌ 85m | ⚠ pre-push hook silently dirtied an unrelated binary asset (caught, not committed) |

### Metrics

| Metric | Duration |
|--------|----------|
| Total wall-clock | ~3h 44m |
| Hands-on | ~80 min (36%) |
| Automated agent time | ~144 min (64%) |
| Idle/testing/away | included in agent time above (background builds/tests ran concurrently with conversation) |
| Retro analysis time | ~5 min |

### Key Observations
- The agent worked most independently during the ~67-minute early research stretch (background Explore subagent + WebSearch/WebFetch + `javap` on the real brotli4j jar) and during the final docdb-studio fix — both were genuinely open technical questions resolvable by investigation rather than needing user input, and both surfaced real, non-obvious findings (Compression Dictionary Transport's actual mechanics; a mismatched Brotli dictionary decodes silently wrong rather than failing loudly).
- Most user interaction was in the schema-design phase: the user corrected the design three times (per-row dictionary/no-dictionary flag → single dictionary embedded in the database → "convert everything, never retrain"). Each correction was a real simplification, but it took ~5 rounds to converge — see the new CLAUDE.md guidance below.
- Two real, avoidable gaps got caught and fixed during WebServer.kt testing: a missing `Brotli4jLoader.ensureAvailability()` call in a new JVM test (a quick grep of 4 existing call sites would have caught it before the first failed run), and the app module's test dependencies never having a desktop-native brotli4j artifact wired in at all (pre-existing, unrelated to this session, but only surfaced now that a test actually exercised brotli4j's real decoder on JVM).
- One near-miss handled well, not turned into a mistake: the pre-push hook's Gradle invocation (`spotlessCheck`) silently regenerated an unrelated, already-committed binary asset (`assets/core.cgt`, externally-fetched) — caught via `git status`/`git diff --stat` before staging, restored, and excluded from the commit.
- **User feedback (direct):** the whole-database migration script (`migrate_content_to_dictionary_brotli.py`) processed ~30,000 Content rows strictly sequentially, each spawning its own `brotli` subprocess — this should have been parallelized proactively rather than accepted as a slow serial run. Fixed post-hoc: a `ThreadPoolExecutor`-based rewrite measured 3-6x faster on synthetic benchmarks, pushed as an update to the still-open PR.

### Feedback
**What worked:** Not directly asked before the session's main work concluded; the user's engagement pattern (terse directives, decisive corrections, real-device verification) mirrors prior sessions' noted preference for low-friction, high-trust collaboration.
**What didn't:** "Claude could have offered to parallelize the conversion of the old database format to the new database format. Doing thousands of rows in the content table took a long time and could have been ten times faster with parallelization." (direct user feedback, acted on immediately)

### Actions Taken

| Issue | Action Type | Change |
|-------|-------------|--------|
| No guidance to grep for existing native/loader-API call sites before writing new code against one (missed `Brotli4jLoader.ensureAvailability()`) | CLAUDE.md | Added bullet to Project-specific constraints: grep for existing init/loader call sites; verify a version-catalog native/desktop artifact is actually wired as a dependency, not just declared |
| Pre-push hook's Gradle run silently regenerated an unrelated tracked asset (`assets/core.cgt`) | CLAUDE.md | Extended the existing binary-asset-provenance bullet: any Gradle invocation (including hooks) can silently re-fetch these assets; check `git status`/`git diff --stat` before broad staging |
| Schema design redirected 3x before converging | CLAUDE.md | Extended "Plan and size before building": for cross-repo/hard-to-migrate schema changes, state the proposed design explicitly and pause for confirmation before writing code |
| Migration script ran serially over ~30,000 rows instead of being offered/built parallel from the start | CLAUDE.md + code fix | Added standing rule (default per-row batch/migration scripts to parallel execution when dominated by process-spawn/I/O overhead); also parallelized `migrate_content_to_dictionary_brotli.py` itself via `ThreadPoolExecutor`, pushed to the open PR |
| Brotli dictionary-mismatch decode behavior (silently wrong, not a reliable failure) | learnings.md | New "Brotli with a custom/raw dictionary" section (4 entries: mismatch behavior, direct-`ByteBuffer` requirement, cross-tool verification approach, Python `brotli` package's lack of dictionary support) |
| ADB/USB flakiness (MTP mode + hub routing dropped the connection repeatedly) | learnings.md | New "Android on-device testing (adb)" section: `lsusb -t`/`/sys/bus/usb/devices/*/speed` diagnosis technique |
| `monkey -c LAUNCHER` opened LeakCanary's debug-build launcher instead of the app | learnings.md | Same section: use `am start -n <pkg>/.activities.<RealActivity>` explicitly in debug builds bundling LeakCanary |
| Batch-script parallelization pattern (SQLite thread-safety, `:memory:` vs real file for multi-connection tests) | learnings.md | New "Batch/migration script performance" section (3 entries) |
| Called `ScheduleWakeup` outside a `/loop` context (self-caught, reverted) | learnings.md | New "Claude Code tooling" section: that tool is `/loop`-mode only; background `Bash` tasks already self-notify |
| ~67 minutes of empirical pre-verification (WebView/brotli4j internals) before writing code | No action | One-off judgment call on a genuinely non-obvious correctness risk, not a repeatable repo-specific policy |

## 2026-08-13 - ADFA-5088: individual Preferences/Plugin Manager tooltips + docdb SQL scripts

### Time Breakdown

| Started | Phase | 👤 Hands-On Time | 🤖 Agent Time | Problems |
|---------|-------|-----------------|---------------|----------|
| Aug 12, 7:38am | Setup & research (branch, ticket, docdb schema + Preferences tag investigation via 2 background agents) | ▏ ~3m | ████ 42m | |
| Aug 13, 5:12am | Implement fixup commits + fold in Plugin Manager screen (investigated via background agent, then implemented) | ▌ ~5m | █████ 45m | ⚠ mid-session scope addition |
| Aug 13, 6:01am | Architecture review + open PR + Jira update | ▏ ~1m | ███ 28m | |
| Aug 13, 6:30am | Code review response — verified findings, discovered stale local DB, rewrote SQL scripts | ▋ ~6m | █ 13m | ⚠ near-miss: caught mid-review only because the user pushed back |
| Aug 13, 6:49am | Fixups, ADFA-5121 follow-up ticket, wrap-up | ▎ ~2m | ▋ 7m | |
| Aug 13, ~7:00am | Second review round: fail-fast SQL fix (`.bail on` + guard table), validated against user-supplied real DB copies (est.) | ▌ ~8m | ████ 35m | ⚠ `.system` shell-parsing rabbit hole before finding the right fix |
| Aug 13, ~8:00am | Retro + 2 follow-up PRs (docdb doc gotchas, CLAUDE.md provenance rule) (est.) | ▊ ~10m | ███ 25m | |

*(A ~20.9h overnight gap between the first two phases is excluded from the bars/percentages below as idle time, not work. The last two rows are estimated from context, not re-run through the transcript-analysis script.)*

### Metrics

| Metric | Duration |
|--------|----------|
| Total active wall-clock | ~4h |
| Hands-on | ~35 min (15%) |
| Automated agent time | ~195 min (85%) |
| Idle (overnight, between sessions) | ~20.9h (excluded above) |
| Retro analysis time | ~3 min (script run) + manual extension for later phases |

### Key Observations
- **The one real near-miss**: a SQL script was built and validated against `assets/documentation.db` — a 213MB file that's `.gitignore`d and downloaded by a Gradle task, not a committed repo asset. Its schema and content were treated as ground truth (including writing "confirmed via sqlite3" claims into the script's own header) without ever running `git ls-files`/`git check-ignore` on it. The local copy was stale; the real database already had curated production content for 5 of the tags the script was about to write to, which would have been silently overwritten. Caught only because the user independently checked the schema and pushed back.
- **Second review round found a related, second-order bug**: a `BEGIN;...COMMIT;` wrapper without `.bail on` doesn't actually give atomicity — verified empirically that a mid-script SQL error still lets `COMMIT` through with whatever succeeded before it. Fixed with `.bail on` plus a temp-table `CHECK` constraint that turns a silently-empty Brotli payload into a catchable SQL error.
- **A costly (but ultimately abandoned) detour**: significant time went into reverse-engineering a content-dependent shell-parsing failure in the sqlite3 CLI's `.system` dot-command (some strings triggered a dash syntax error, most didn't, with no clean single hypothesis found). The eventual fix sidestepped the problem entirely — kept `.system` lines simple and did the fail-fast check in SQL instead of shell chaining — rather than continuing to chase the CLI quirk's root cause.
- **Good pattern reinforced twice**: both times a bulk SQL rewrite was needed under time pressure, it was done via a small Python script parsing and regenerating the statements programmatically, rather than hand-editing 60+ lines — this avoided introducing new content errors while doing a structural change.
- **Real-world validation loop with the user**: the user independently ran the scripts against copies of the real database (`.save`, current, `.new`) and handed back concrete artifacts (file paths, MD5 comparison) rather than descriptions — this was more useful than any amount of scratch-DB testing alone, and surfaced that an earlier script version had already partially, successfully applied to the "before" copy.

### Feedback
**What worked:** Not directly stated this session — inferred from the user's engagement pattern (quick short replies, handing over real artifacts to check rather than describing them).
**What didn't:** "Don't make assumptions about large binary files. They may be maintained and updated outside the repository." (direct user feedback, in response to the stale-DB near-miss)

### Actions Taken

| Issue | Action Type | Change |
|-------|-------------|--------|
| No standing guidance against treating a large binary asset's on-disk content as ground truth without checking provenance | CLAUDE.md | Added a bullet to "Project-specific constraints": check `git ls-files`/`git check-ignore` and how an asset is provisioned before trusting its schema/content — generalizes beyond docdb to ~6 other gitignored, externally-fetched assets in `app/build.gradle.kts` |
| `documentation.db`-specific provenance and SQL-authoring gotchas (`.system` chaining, `.bail on` + guard-table pattern) not documented anywhere a future SQL-script author would find them | Doc | `docs/documentation-database.md` updated via PR #1666 (ADFA-5123): provenance warning in "Where it lives", new "Writing one-off SQL scripts against this database" subsection |
| Dead `UseSytemShell` preference (class never instantiated, underlying setting never read elsewhere) found while auditing for tooltip coverage | Ticket | Filed ADFA-5121 |

## 2026-07-24 - LeakCanary icon shrink (ADFA-4843), JAXP/PDF.js investigations (ADFA-1491/ADFA-3304), and full blankj:utilcodex removal (ADFA-4649)

### Time Breakdown
| Started | Phase | 👤 Hands-On Time | 🤖 Agent Time | Problems |
|---------|-------|-----------------|---------------|----------|
| Jul 24 7:52pm | LeakCanary (ADFA-4843): investigate → decide → build → PR | ██ 4m | ██ 18m | |
| Jul 24 8:29pm | Retro attempt #1 (interrupted before analysis ran) | | | ⚠ Interrupted, no work saved |
| Jul 24 8:33pm | Ticket triage: ADFA-1491 + ADFA-3304 deep investigation (no code shipped) + PR #1572 review-bot polling loop | █ 2m | █████ 42m | ⚠ ~40m of research produced zero shipped code (correctly — both tickets' real scope was smaller/harder than assumed) |
| Jul 24 9:31pm | ADFA-4649: full 7-stage blankj-utilcode removal (71 files, 20 util classes) → PR | █ 1m | █████████ 96m | ⚠ One same-turn fix: `BaseApplication`/`IDEApplication.foregroundActivity` override collision, caught by compiler immediately |
| Jul 24 11:13pm | APK size measurement, push, PR, architecture review + doc fix | █ 2m | ██ 8m | |
| Jul 24 11:44pm | Jira progress, retro resume | █ 1m | | |

### Metrics
| Metric | Duration |
|--------|----------|
| Total wall-clock | ~3h 52m |
| Hands-on | ~58 min (25%) |
| Automated agent time | ~126 min (54%) |
| Idle/testing/away | ~48 min (21%) |
| Retro analysis time | ~2 min |

### Key Observations
- The ADFA-4649 removal (96 min, one continuous turn) was the standout: 7 staged commits, each independently compiled/spotless'd/tested, zero user intervention needed mid-stream — one instruction ("do this in stages") ran to completion.
- The user consistently gave short, high-trust directives ("push and open a PR", "add the comment and post the review") rather than step-by-step guidance — low-friction collaboration.
- Two tickets (ADFA-1491 JAXP, ADFA-3304 PDF.js/docdb) got real investigation but shipped no code — in both cases the actual scope was smaller or more infrastructure-entangled than the ticket implied, and findings were posted instead of forcing a low-value change. Good triage, but ~40 min of agent research for zero shipped code is worth naming.
- Unresolved: the `@claude review` bot on PR #1572 never responded in this session (no result, no overage message either) — the polling loop just stopped when other work took over, not because it resolved.
- Only two full `:app:assembleV8Debug` builds ran in the whole session (final verification + before/after size comparison); all per-stage verification used targeted, batched `:module:compileV8DebugKotlin` calls — already the efficient pattern, but build wait time was still the user's named friction point.

### Feedback
**What worked:** Staged, verified commits for the big refactor — breaking ADFA-4649 into 7 easiest-to-hardest commits, each compiled/tested/spotless'd before moving on.
**What didn't:** Waiting for the build system to create an APK — inherent friction in this multi-module Android project, not a request to change approach.

### Actions Taken
| Issue | Action Type | Change |
|-------|-------------|--------|
| No standing guidance to prefer targeted compiles over full assembles during iteration | CLAUDE.md | Added a "Fast iteration" bullet to Build & test: batch targeted `:module:compileV8DebugKotlin` calls during iteration, reserve `:app:assembleV8Debug` for final verification |
| Staged-refactor pattern that worked well wasn't captured as standing guidance | CLAUDE.md | Extended "Plan and size before building": staged multi-commit refactors should order easiest→hardest and verify each stage before advancing |
| `Handler.removeCallbacks` same-instance requirement, javap-verification technique, MockK extension-function static mocking, git-worktree before/after comparison | docs/process/learnings.md | Added 4 new learnings entries |
| Two zero-code ticket investigations (ADFA-1491, ADFA-3304) | No action | Already covered by existing "post progress / comment findings" guidance; no gap |
| Stalled `@claude review` bot polling (~45 min, no resolution) | No action | Single inconclusive data point — too thin to generalize into a standing timeout/give-up rule yet |
