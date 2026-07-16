### How the incremental compile works

We don't do the dependency analysis — Kotlin does. We hand its Build Tools API (`CompilationService`) the full source list plus the set of files that changed, and it works out what actually needs recompiling. That's the main reason we drive the Build Tools API rather than calling the compiler directly.

It keeps two caches: a lookup cache recording which symbols each file resolved, and member-level ABI snapshots of each class. On an edit it recompiles the changed file, diffs the new ABI against the snapshot, marks every file whose lookups touched a changed symbol as dirty, and iterates until nothing new turns up.

So the blast radius depends on *what* changed, not on how much text changed:

| Edit | What recompiles |
| --- | --- |
| Method body, signature unchanged | just that file |
| Method signature | the callers, iterated |
| `const val` | every call site — the value is inlined at compile time |
| `inline` function body | every caller — the body is copied into call sites |
| New public method or class | files that referenced it |

The dirty set also drives DEX: more dirty files means more changed classes, and DEX scales with class count (423ms at 20 classes → 1116ms at 200).

**Caveat: we've only measured the smallest changeset.**

The flat ~71ms-at-30k-LoC number is a best case, not a typical one. The benchmark app is a star — `Main` calls each `Leaf.value()` and the leaves don't depend on each other — and the edit is a body-only change to a function nothing calls, so the fan-out is zero by construction. Every row of that table except the first is untested. "Recompiles the changed file + ABI-affected downstream" describes what the engine does, not what we measured.

That matters for Goal #2. A one-character `const val` change can dirty hundreds of classes and grow both compile and DEX, while a 600-line rewrite of a single file stays flat — the opposite of the intuition that small edits are fast. We don't know yet whether the wide cases hit 2s. This needs an edit-type matrix (body / signature / const / inline / new symbol) measured on a real app rather than the synthetic one.

### Concurrency & Error Reporting

The user keeps editing while a build runs, so there's usually more than one outstanding change. A build takes 0.5–2s, so a save landing inside that window is the normal rhythm of typing, not an edge case — and changes also arrive from `git pull`, Termux, plugins, and agents. We need a concurrency model regardless of what we decide about triggers.

**Rules**

1. **One build at a time.** The Kotlin incremental caches are stateful; two compiles against them concurrently would corrupt them.
2. **Coalesce, don't queue.** One build in flight plus one pending changed-file set. Extra triggers set a flag instead of enqueuing a build, so we can't fall behind a fast typist.
3. **Clear the pending set only after a build succeeds.** The prototype clears it *before* compiling, so a failed compile drops those edits: edit A and B, save, B has a typo, fix B, save — A is never recompiled and the banner still goes green. Silently stale output is worse than a slow build.
4. **"Nothing changed" is not "unknown".** The prototype treats an empty changed set as "recompile everything" (~30× slower at 30k LoC). Only a genuinely unknown change should force a full compile.
5. **Don't cancel a running compile.** Let it finish, then rebuild from the accumulated set. Cancelling starves the user — every keystroke kills the build that would show them their change — and risks corrupting the incremental caches, whose recovery is a full rebuild.
6. **Write files atomically** (temp + rename) and watch for close/move, not modify, or the compiler can read a half-written file.
7. **Payload generations stay monotonic** and the test app rejects anything not newer than what it has. (The prototype already does this.)

**Surfacing errors**

The invariant — the test app always reflects the project — means the UI must never claim success over stale output.

- **Tag diagnostics with the generation they came from and discard stale ones.** Otherwise build N's errors land after the user has already fixed them, and we're moving the ground under someone who is mid-fix.
- **The status surface must clear on failure**, not only on a successful render. (Prototype bug: a compile error, or a crash inside the payload, leaves "Compiling…" stuck showing stale content.)
- **Distinguish a compile error from a payload crash at runtime.** Only the first is fixable in the editor; a compile error should give file:line and jump there.
- **On failure, say what's actually running** — the test app is showing generation N-1, not the code on screen.

Open question: do we surface errors in the test app, in CoGo, or both?
