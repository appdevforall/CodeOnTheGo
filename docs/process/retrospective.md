# Retrospective Log

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
