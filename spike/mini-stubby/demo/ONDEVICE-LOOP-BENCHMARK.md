# On-device live-reload loop — measured on A56 (ADFA-4128)

Full loop running **entirely on the A56** (CoGo's bundled JDK 21 + d8 + aapt2), no Mac.
Project = the Lemonade payload (`Main.kt`, ~23 KB / ~600 lines, single file).

## Warm steady-state (idle device, well-warmed daemon), 5 successive `.kt` edits

| edit | total | kotlinc | d8   | pack | deploy | shell reload (detect→render) |
|------|-------|---------|------|------|--------|------------------------------|
| 1    | 3095  | 2455    | 435  | 166  | 1 ms   | ~65 ms |
| 2    | 2471  | 2150    | 167  | 119  | 1 ms   | ~65 ms |
| 3    | 2698  | 2179    | 321  | 168  | 1 ms   | ~65 ms |
| 4    | 2323  | 1956    | 193  | 146  | 1 ms   | ~65 ms |
| 5    | 2390  | 2019    | 172  | 153  | 1 ms   | ~65 ms |
(ms unless noted). Cold first compile (paid once at daemon startup): ~13 s.

## Read

- **Save→rendered ≈ 2.4 s warm**, dominated by **kotlinc (~2.0 s)**. Dex (~0.2 s),
  packaging (~0.15 s), deploy (~1 ms, in-process serve), and the **in-place reload
  (~65 ms)** are all negligible — exactly the shape the design predicted (compile is
  the wall; load/package/install is ~nothing).
- The JIT/IC warmed visibly over the run (kotlinc 3789 → ~2000 ms across edits).
- **Why ~2.0 s, not the 0.53 s E-series figure:** incremental-compile granularity is
  **per-file**. Any edit to this ONE 23 KB `Main.kt` recompiles the whole file. The
  0.53 s benchmark changed a single function in a small synthetic file. Splitting the
  payload into smaller files would cut kotlinc substantially — the lever for the next
  round. (Total *project* size barely matters; changed-*file* size is what costs.)
- Reload + dex + deploy prove the "no install, in-place" claim holds on-device: the
  user-visible tail after compile is ~0.25 s.
