# Retrospective Log

## 2026-08-18 - ADFA-5172/5175/5176: the local WebServer's 1 s stall, and removing the socket instead

### Time Breakdown

Each phase's span runs from its first prompt to the next one, so the spans sum to the wall clock
below, to within a minute of rounding. A span holds both agent work and any time nobody was at
the keyboard; only the totals in Metrics attempt that split, and only as an estimate. Hands-on is per-phase raw, so it sums slightly
above the adjusted total, which merges overlapping turns into one buffer.

| Started | Phase | 👤 Hands-On Time | 🤖 Span (agent + away) | Problems |
|---------|-------|-----------------|------------------------|----------|
| Aug 17 9:42pm | Ticket read + accept-loop instrumentation | ▊ 7m | █▏ 12m | |
| Aug 17 9:54pm | Build, drive, root-cause the stall | ▌ 5m | ███ 34m | ⚠ HelpActivity not exported, so the measurement needed a throwaway manifest tweak; one flaky arm |
| Aug 17 10:28pm | Keep-alive design + ADFA-5175 filed | █ 10m | █ 10m | |
| Aug 17 10:37pm | ADFA-5175 stage 1, transport pivot, ADFA-5176 spike | ▊ 8m | ███████████ 109m | ⚠ 3 Spotless whole-file reformats; direction changed mid-implementation |
| Aug 18 12:26am | Extraction onto the ADFA-5153 base | ▌ 5m | █████████████ 133m | ⚠ merge conflicts, plus a stale KDoc and dangling brace from moving code by script |
| Aug 18 2:39am | Tests, Pebble move, cleanup, two PRs | ▊ 8m | ██████████████ 142m | ⚠ tests written just before the API they cover moved |
| Aug 18 5:01am | Review fixes, CodeRabbit replies, retro | █▏ 12m | ██████████████████ 182m | |

### Metrics

| Metric | Duration |
|--------|----------|
| Total wall-clock (first prompt to last) | 10h 21m (621m) |
| Hands-on | 53m (9%) |
| Automated agent time (estimated) | ~6h 20m (380m, 61%) |
| Idle/testing/away (estimated) | ~3h 8m (188m, 30%) |
| Retro analysis time | 6 min |
| Cost | $347.53 (495 API calls, 618K output tokens) |

Wall-clock is exact, from the message timestamps. Hands-on is the transcript script's adjusted
figure. The last two are an estimate of how the 568 minutes that are not hands-on divide, since
nothing in the transcript marks when the agent stopped working and the user walked away; they are
sized from the work performed (build and test runs, device measurements, an adb pull of a 267 MB
database) and add up to the wall clock rather than being measured independently.

13 user messages, most of them one to three words. Only user-message timestamps are exact, so the agent/idle split is estimated from the work performed.

### Key Observations
- The two longest unattended stretches were the most productive: "build and drive" (30m, root cause established with kernel counters and a control-listener comparison) and "proceed" (130m, a cross-module extraction, built and device-verified). Three-word prompts, high leverage.
- **The most valuable question came from the user, and should have come from the agent.** "Could we use a different transport?" arrived *after* ADFA-5175 was filed and keep-alive was already being built. The agent's own evidence -- drop rate scaling with connection *rate* -- pointed at "open fewer connections", and `shouldInterceptRequest` was the obvious mechanism. It designed a way to tune the mechanism instead of asking whether the mechanism was needed. Result: a filed ticket whose plan was invalidated a day later, and the keep-alive work stopped after stage 1.
- Rework was formatting tax and transplant fixups, not logic: three whole-file Spotless reformats (~500 whitespace lines, kept out of behavioral diffs by hand), and 4-5 failed python patch asserts from over-long match anchors.
- Zero substantive corrections from the user across 13 messages. Steering, not fixing.
- The device work needed a temporary `android:exported="true"` on HelpActivity to be scriptable at all; it was kept on a throwaway branch and reverted, but it is a recurring cost of driving activities that are (correctly) not exported.
- The retro script counted the agent's own screenshot reads as user turns. Fixing it moved hands-on **down**, from 57.4 to 52.9 minutes, as predicted -- six phantom turns lose their per-turn buffer and typing time, while their assistant output is re-attributed to the real prompt that caused it, so reading time is unchanged at 41.6 either way. (An earlier version of this entry reported 51 -> 53 and explained the rise; both numbers came from runs against different lengths of a transcript that was still growing, since the script always reads the whole file. Re-run against one fixed slice, the metric can only fall: reading is conserved by construction and the other two components shrink.)

### Feedback
**What worked:** Autonomy. The long unattended stretches were where the value was.
**What didn't:** The transport question should have come from the agent, not the user.

### Actions Taken

| Issue | Action Type | Change |
|-------|-------------|--------|
| Designed keep-alive to tune a mechanism before asking whether the mechanism could go | CLAUDE.md | "Plan and size before building": new bullet -- when the evidence scales with a rate or volume, check whether the platform can remove the mechanism before planning the tuned version, citing ADFA-5172/5176 |
| Ratchet reformats risk burying behavioral diffs | CLAUDE.md | Code style: state the convention -- land a whole-file reformat as its own commit, before the behavioral one, and say so in its message |
| `ServerConfig`-style defaults that call framework APIs break any new JVM test | learnings.md | Added under Android / Kotlin, with the failure mode (constructor throws before the test body runs) |
| Testing WebView interception without Robolectric | learnings.md | Added under MockK: `mockkStatic(android.os.Environment::class)` plus a mocked `Uri`, and split the decision from the framework construction |
| How in-process WebView serving actually behaves | learnings.md | New "Serving content to a WebView" section: interception matches any URL so existing URL spaces need no rewriting; no response decoding; no POST body; no 206; WebView cannot render a PDF |
| Android system SQLite may lack JSON1 | learnings.md | New "Android system SQLite" section, cross-referenced to ADFA-5179 |
| Retro script counted screenshot reads as user turns | Skill | `analyze_transcript.py`: filter `[Image: original NxN...]` tool results out of the human role |
| Bookshelf 500s where SQLite lacks JSON1 | Ticket | ADFA-5179 (Bug), linked to ADFA-5176 |
| Documentation PDFs render blank in HelpActivity | Ticket | ADFA-5180 (Bug), linked to ADFA-5176 |
| Tests written just before the API they cover moved | No action | One-off: the risk was flagged and the order was chosen deliberately; cost was ~10 lines of test edits |

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
