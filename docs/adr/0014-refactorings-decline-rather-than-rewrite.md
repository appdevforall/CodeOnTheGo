# 0014. Interactive refactorings decline rather than rewrite unselected code

- **Status:** Proposed
- **Date:** 2026-08-10
- **Deciders:** Code On The Go team

## Context

The K2 Kotlin LSP is growing a family of interactive refactorings: extract variable (ADFA-4826), extract method (ADFA-5080), inline variable (ADFA-4827), semantic rename (ADFA-4825). [ADR 0013](0013-refactoring-ui-lives-in-the-owning-lsp-module.md) settles where their UI lives and that analysis produces plain data. It says nothing about how capable they should be.

That question turns out to dominate the requirements. Designing extract method surfaced a run of cases where the transformation the user asked for cannot be performed by *moving* their code - it also needs the moved code's interior edited, or a guess about intent:

- A `var` declared outside the selection and reassigned inside it. Kotlin has no `out` parameters, so the faithful emission is a parameter plus `var x = x` at the top of the body - which compiles, with a name-shadowing warning.
- Two or more values flowing out of the selection. There is no tuple to return that the user would have written themselves.
- A `return` in the middle of the selection. Real IDEs encode the exit in a nullable or sentinel return and re-test it at the call site.
- Members of an enclosing `with`/`apply`/`run` receiver used unqualified. They can only survive as a parameter if every unqualified access inside the body is qualified.
- A type parameter declared on the enclosing function. It needs a filtered copy of the type-parameter list with its bounds.

Desktop IDEs handle most of these, and their users accept the result because they can read a multi-file diff, undo granularly, and fix up whatever the refactoring got slightly wrong. Code On The Go's users are on a phone: a small screen, no side-by-side diff, imprecise touch selection, and - per ADFA-5081 - a code-action edit history that is not even reliably one undo step yet. Many are also students, for whom generated code carrying a fresh compiler warning is indistinguishable from a broken tool.

## Decision

**An interactive refactoring moves the user's code. It does not edit the interior of what it moved, and where it cannot transform faithfully it declines with a specific, actionable reason.**

Concretely:

- **Refusal is a designed outcome, not an error.** Each refactoring's plan carries a typed reason (extract method: `ExtractionRefusal`), and each reason has its own user-facing message naming the construct in the way - "the selection assigns to `total`, which is declared outside it", not "cannot extract".
- **Prefer excluding a case by construction over filtering it later.** Extract method accepts only sibling statements in one block; extract variable rejects bare literals and expression fragments up front. Both remove whole classes of hard case before any analysis runs.
- **Prefer a stricter rule to a cleverer one** when strictness costs capability and cleverness costs certainty. Extract method refuses a reassigned outer `var` even when the write is provably dead, because proving it needs liveness analysis.
- **Never emit code that does not compile, and avoid emitting code that warns.** The two modifiers extract method *does* add - `suspend` and `@Composable` - are required precisely because omitting them breaks compilation.
- **A refusal is a backlog item, not a dead end.** Where the refused case is common, file it: ADFA-5082 tracks the reassigned-`var` output.
- **Where part of the request is sound, apply that part and say so.** A refactoring has three outcomes, not two: apply, refuse, or **apply partially**. Inline variable (ADFA-4827) is the case that needs the third - a variable whose value is reassigned partway through can be inlined at the references before the write and nowhere after it, so refusing the whole thing would discard a sound transformation of the earlier half. A partial application must report both counts and what it left behind, and it must leave the file compiling on its own, exactly as a full application does. It is not a licence to apply the doubtful part and hope.

This applies to the whole refactoring family, not just extract method. Inline variable and rename inherit it - inline variable adding the third outcome above, rename presumed to need only the first two until its design says otherwise.

**Out of scope of this decision.** Whether a refactoring may duplicate an expression that is evaluated more than once. Inline variable does, without checking for side effects (see [kotlin-inline-variable.md](../features/kotlin-inline-variable.md)): the emitted code compiles, carries no warning, and is the user's own expression unedited, so nothing above forbids it. Kotlin offers no way to prove purity, so a check would be a heuristic rather than a stricter rule, and this ADR prefers strictness to cleverness in both directions.

## Consequences

**Positive**

- Every applied refactoring produces code the user could have written, so the feature earns trust on a device where verifying the result is expensive.
- Refusal reasons are cheap to specify, cheap to test (one case each) and cheap to QA, where a clever transformation needs its own test matrix and its own failure modes.
- The rules are stateable in a sentence each, which is what makes the feature docs reviewable by someone who has not read the implementation.
- Excluding cases by construction keeps the analysis pass small, which matters when it runs on a phone.
- Partial application recovers capability that an all-or-nothing rule would throw away, without weakening the compile-and-do-not-warn guarantee: the sound part is applied and the doubtful part is simply not touched.

**Negative / costs**

- The refactorings are visibly less capable than a desktop IDE's. Two of extract method's refusals - a reassigned outer `var` (the accumulator loop) and an enclosing `with`/`apply` receiver (pervasive in Android code) - will be hit routinely.
- The quality of the *messages* becomes load-bearing. A generic refusal reads as a broken feature, so this decision spends translated strings: roughly seven for extract method alone.
- Users arriving from IntelliJ will read some refusals as regressions rather than as design.
- A partial application is harder to *report* than either other outcome, and harder to QA: the message has to convey two counts and a surviving declaration in one flash, and every "how many were left behind" case is its own test. A partial result the user misreads as a complete one is the failure mode to watch.
- The line is a judgement, not a formalism. "Editing the interior of the moved code" is clear in the cases above but will need re-application, case by case, in each future refactoring.

## Alternatives considered

- **Match desktop IDE capability.** Handle multiple outputs, mid-selection returns, receiver capture and type parameters, as IntelliJ does. Rejected: each requires rewriting the body's interior or inventing a signature the user did not ask for, and the cost of getting it subtly wrong is paid on a device where the user can least easily see it.
- **Transform, but warn.** Apply the refactoring and flash a caveat ("check the result"). Rejected: it puts the verification burden on the person least equipped to do it, and a warning shown once is gone before the user reads the code.
- **Transform behind a setting**, off by default. Rejected: it doubles the behaviour to test and support for a feature whose hard cases are exactly the ones a setting's users would hit first. Revisit only if specific refusals prove to be common complaints - which is what ADFA-5082 exists to measure.
- **One generic refusal message.** Cheapest, and consistent with extract variable's single "nothing to extract". Rejected as a direct consequence of this decision: if declining is the primary answer in hard cases, the decline has to teach.

## Related

- [ADR 0013](0013-refactoring-ui-lives-in-the-owning-lsp-module.md) - where refactoring UI lives; this ADR answers *how capable it is*
- [ADR 0010](0010-navigation-resolves-via-analysis-api.md) - the K2 Analysis API as the Kotlin semantic source of truth
- [kotlin-extract-method.md](../features/kotlin-extract-method.md) - R7 to R10 and R14 are this decision applied case by case
- [kotlin-extract-variable.md](../features/kotlin-extract-variable.md) - the shared vocabulary and primitives
- [kotlin-inline-variable.md](../features/kotlin-inline-variable.md) - R6 to R9 are this decision applied to a subtractive refactoring, and the origin of the third outcome
