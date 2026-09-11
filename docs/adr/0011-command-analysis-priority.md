# 0011. User-invoked commands get their own analysis priority

- **Status:** Proposed
- **Date:** 2026-08-03
- **Deciders:** Code On The Go team

## Context

Analysis in the K2 Kotlin LSP is serialised behind one priority lock (`AnalysisScheduler`). Until now it had three tiers:

| Priority | `supersedesSamePriority` | Preempted work |
|---|---|---|
| `INDEXING` | false | re-queued |
| `DIAGNOSTICS` | false | re-queued |
| `INTERACTIVE` | **true** | **discarded** |

`INTERACTIVE`'s defining property is *"a newer request of the same priority makes me stale, so discard my work"*. That is exactly right for completion and signature help: they fire on keystrokes, and an in-flight result for text the user has already moved past is worthless.

It is wrong for a command the user invoked from the code-actions menu. The user tapped a menu item and is watching a progress flashbar; the request is not stale, and discarding it silently produces a wrong answer rather than no answer. Yet three commands sat on `INTERACTIVE`:

- `GoToDefinitionAction` - discovered the problem and worked around it with a one-shot retry (ADFA-4823).
- `OrganizeImportsAction` - no retry. A completion request discards it and it silently does nothing.
- `ImplementMembersAction` - same.

Find usages (ADFA-4824) makes this acute. It is user-invoked, runs one analysis session per candidate file, and can take seconds across a workspace. On `INTERACTIVE` a single keystroke anywhere would discard an in-flight file's work, and two concurrent searches would discard each other.

## Decision

**Add a fourth priority, `COMMAND`, for user-invoked commands, ordered between `DIAGNOSTICS` and `INTERACTIVE`, with `supersedesSamePriority = false`.**

```text
INDEXING < DIAGNOSTICS < COMMAND < INTERACTIVE
```

- Every user-invoked command runs at `COMMAND`: find usages, go-to-definition, organize imports, implement members.
- `supersedesSamePriority = false`, so **two commands never discard each other**; the second waits for the lock.
- Keystroke-driven features (completion, signature help) stay on `INTERACTIVE` and therefore still win against a command.
- A command preempted by `INTERACTIVE` retries. Long-running commands take their session **per unit of work** - for find usages, per candidate file - so a preemption costs one file, not the whole request.

## Consequences

**Positive**

- The silent-failure bug in organize-imports and implement-members is fixed, not just in the one action that happened to notice it.
- Commands stop competing destructively with each other, which is what makes a multi-file search viable at all.
- Typing responsiveness is untouched. On a phone, completion is part of how text gets entered; starving it is the one regression a user would feel immediately.
- The priority now says what it means. `INTERACTIVE` is "stale on newer input"; `COMMAND` is "explicitly requested, must finish or be cancelled".

**Negative / costs**

- Commands still need a retry policy, because `INTERACTIVE` outranks them. The retry is one line at each call site and already proven in `findDefinitionAt`, but it is a rule every future command has to remember.
- Four tiers instead of three is more scheduler surface to reason about.
- Background diagnostics now lose to any command, so a long search delays diagnostics for its duration. Acceptable: diagnostics are re-queued, never discarded.

## Alternatives considered

- **`COMMAND` above `INTERACTIVE`** - rejected, though tempting. Nothing could preempt a command, so retries would disappear everywhere and the two buggy actions would be fixed for free. But a multi-second search would then starve the completion popup for its whole duration, and releasing the lock between files would not help - the command wins it straight back. Fixing *that* means teaching the scheduler to yield to waiting requesters between chunks, which is new machinery for a case only find usages hits.
- **Keep commands on `INTERACTIVE` and add a retry to each** - rejected: it leaves `supersedesSamePriority = true` applying to requests that are never stale, so two commands still discard each other, and every command pays for a property none of them want.
- **Flip `INTERACTIVE.supersedesSamePriority` to false** - rejected: completion genuinely needs discard-on-newer. Rapid typing would otherwise queue a chain of results for text the user has already left.

## Related

- [ADR 0010](0010-navigation-resolves-via-analysis-api.md) - Kotlin navigation resolves via the Analysis API, not the symbol index
- [docs/features/kotlin-find-usages.md](../features/kotlin-find-usages.md) - the feature that forced the distinction
- `lsp/kotlin/.../compiler/modules/AnalysisScheduler.kt` - the scheduler and priority enum
