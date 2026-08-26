# Build and CI glossary

Vocabulary for build and CI work on Code On The Go. Terms are defined here so that a
word means one thing across code, tickets, PRs, and conversation.

This file is a **glossary only**. It holds no implementation detail and no decision
rationale - decisions live in [docs/adr/](../adr/), structure lives in
[ARCHITECTURE.md](../../ARCHITECTURE.md).

## Terms

**Critical path**
The longest chain of work that must finish before CI reports a verdict. Work that runs
concurrently on another runner is not on the critical path even though it costs time.
Distinct from *runner occupancy*.

**Runner occupancy**
Total runner-minutes a single push consumes, summed across every job it starts. A push
can have a short critical path and high occupancy (two runners busy in parallel).
Occupancy is what makes other people's builds queue; critical path is what makes one
developer wait. Reducing one can increase the other.

**ABI change** (of a module)
A change to a module's public compile-time surface: signatures, public constants,
anything a dependent module compiles against. Dependents must recompile. Contrast
*non-ABI change* (a method body, a comment) where dependents need not recompile.
Java and Kotlin **inline** compile-time constants such as `static final String`, so
changing a constant's *value* is an ABI change even though the declaration is untouched.

**ABI churn**
An ABI change that carries no semantic meaning for dependents, forcing recompilation
for nothing. Build metadata stamped into a widely-depended-on module is the canonical
source - see [ADR 0012](../adr/0012-volatile-build-metadata-out-of-abis.md).

**Build graph health**
How closely the set of re-executed tasks matches the set of genuinely affected tasks.
Measured as the ratio of `executed` to `up-to-date`/`from-cache` tasks in Gradle's
summary line. Independent of hardware, and therefore comparable across machines -
unlike wall clock.

**Baseline**
A recorded measurement of the pipeline before a change, against which later iterations
are compared. A measurement is only a baseline if it was produced under the same
protocol and scenario as the runs compared to it.

**Scenario**
A deterministic, scripted source change of defined scope, used as a measurement
workload. Scenarios differ in blast radius - no-op, single leaf module, ABI change in
a core module, multi-module - so one pipeline produces a profile rather than a number.

**Warm workspace**
A checkout whose `build/` outputs and Gradle caches survive from a previous run. The
steady state of a self-hosted runner, and the state any representative measurement must
reproduce. Contrast a *cold* build, which no runner ever performs in practice.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) - module map, layering, tech stack.
- [docs/adr/](../adr/) - the decisions and their rationale.
- [CLAUDE.md](../../CLAUDE.md) - build and test invocations.
