# cogo-mcp status checkpoint

**2026-08-11** · branch `ADFA-5083-mcp` · PR
[#1659](https://github.com/appdevforall/CodeOnTheGo/pull/1659) into `stage` ·
ticket ADFA-5083 (In Progress)

## Where this stands

A host-side MCP server that drives Code On The Go over adb. Six tools, 69 tests,
all verified end-to-end against `emulator-5554` through the real MCP transport
rather than by calling handlers directly.

**Working and merged to the branch:**

| Tool | Verified result on device |
|---|---|
| `ping` | `pong` |
| `is_cogo_installed` | `... is installed.` |
| `cogo_home` | reaches `MainActivity`, twice in a row, prefs file intact |
| `list_projects` | 2 projects (both names contain spaces) |
| `list_templates` | 9 templates with descriptions |
| `list_project_files` | correct non-error "no project open" |

Also on the branch: the debug build no longer registers two launcher activities
(LeakCanary's alias is disabled via the boolean resource it already gates on), so
a generic launch resolves to `SplashActivity` instead of the system
`ResolverActivity`.

## Stop here before adding tools

A three-lens code review (correctness, silent-failure, test-quality) found
**fourteen defects, three critical** -- a confirmed command injection, a path
where `cogo_home` overwrites every user preference and reports success, and
`list_project_files` describing a project that is not open. All reproduced on a
real device or by mutation testing.

They are catalogued in [PRIORITIES.md](PRIORITIES.md#review-defects) with file
and line. They outrank the remaining backlog because the faults live in the
*shared* command-building idiom -- every new tool copies them -- and because
nothing runs these tests in CI.

The unifying lesson, worth carrying forward: `exitCode` is checked at all twelve
adb call sites and is the wrong signal at four of them, because those commands
are deliberately written to keep the outer status at zero (`|| true`,
`|| exit 0`, `unzip -p`). Guard the inner failure, or emit an explicit marker --
`listProjectsCommand` already does this with `NO_PROJECTS_DIR`.

## Open questions for the next session

1. **Fix the three criticals?** Recommended before any new tool. Highest-leverage
   single change: make `CogoHomeTest`'s fake return a realistic preferences
   document instead of `""` -- that alone kills the preference-wipe mutation and
   opens up the merge-path and CRLF coverage.
2. **Split the LeakCanary commit into its own PR?** #1659 now spans `mcp/` and the
   shipped debug APK, which is a different review audience. The commit
   (`7e5382a9e`) is self-contained and cherry-picks cleanly.
3. **Wire `mcp/` into CI.** No workflow references it today.

## Resuming

Every Gradle command runs under flox, launched from the repo root -- the
environment's `on-activate` hook aborts if activated from inside `mcp/`:

```bash
flox activate -d flox/local -- bash -c 'cd mcp && ./gradlew test'
flox activate -d flox/local -- bash -c 'cd mcp && ./gradlew run'
```

Formatting runs from the **repo root**, not from `mcp/`: root Spotless does reach
into top-level standalone directories, so `mcp/` uses tabs.

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
```

## Device state left behind

`emulator-5554` is not pristine. Anyone resuming should know:

- It runs a **locally rebuilt debug APK** carrying the launcher fix. It is not the
  same binary as CI produces.
- `cogo_home` has **permanently disabled auto-open-project**
  (`idepref_general_autoOpenProjects=false`). That is the tool working as
  designed, not a leftover, but the app will no longer reopen the last project.
- Onboarding is complete and `core.cgt` is unpacked, so `list_templates` returns
  data. A fresh device returns the "not installed yet" answer instead.
- Of the five directories under `/storage/emulated/0/CodeOnTheGoProjects`, only
  two are valid CoGo projects; the rest are Flutter projects and a template
  archive. That is correct behaviour, not a bug.

## Reading order

- [PRIORITIES.md](PRIORITIES.md) -- the rubric, all 25 backlog items scored, the
  reachability facts measured on-device, the review defects, and seven asks of the
  CoGo app that would make this work dramatically easier.
- [README.md](README.md) -- how to run, test and register the server.
- [TODO.txt](TODO.txt) -- the raw backlog.
- `docs/superpowers/specs/2026-08-10-mcp-server-design.md` -- the design and why
  host-side, loopback, no TLS.
