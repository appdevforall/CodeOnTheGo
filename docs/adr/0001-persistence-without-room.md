# 0001. Persistence uses SQLite/filesystem, not Room

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

Code On The Go stores modest local state — recent projects, tooltips, breakpoints, preferences, and assorted IDE settings — on resource-constrained Android devices, entirely offline. We care about APK size, method count, build time, and full control over storage, and we already run a large multi-module build where every annotation processor adds real cost.

Room, Android's default persistence library, generates code via `kapt`/KSP, ships a runtime, and manages a schema. It saves boilerplate but adds footprint and build overhead, and was applied inconsistently across the codebase.

## Decision

New persistence uses **raw SQLite** (`SQLiteOpenHelper` / `SQLiteDatabase`) or the **filesystem / preferences**. Room is **not** used for new code.

The one exception is the **Recent Projects** feature (`app/src/main/java/com/itsaky/androidide/roomData/recentproject/`, `@Database version = 4`), which predates this decision and is grandfathered in. Do not extend it with new entities or tables. (`idetooltips` still declares unused Room Gradle deps; its tooltip store is raw SQLite — remove those deps.)

## Consequences

**Positive**
- Smaller footprint; no annotation processing for persistence.
- Full control over SQL, migrations, and threading.
- One fewer library to track for CVEs/updates.

**Negative / costs**
- Hand-written SQL, migrations, and row→object mapping.
- No compile-time query verification (a Room benefit we forgo).

**Follow-ups**
- Provide shared SQLite helper utilities to keep raw-SQLite boilerplate consistent and safe (parameterized queries — see SECURITY.md).
- Remove the unused Room dependencies from `idetooltips`.

## Alternatives considered

- **Room everywhere** — rejected: adds `kapt`/runtime/footprint and build cost not justified on constrained devices, and it had crept in unevenly.
- **DataStore / SharedPreferences only** — insufficient for relational/queryable data (e.g. recent projects).
- **A third-party ORM** — more dependencies and surface for little gain over raw SQLite.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — persistence policy (authoritative model).
