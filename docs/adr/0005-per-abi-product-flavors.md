# 0005. Ship per-ABI product flavors (v7/v8), not a universal APK

- **Status:** Proposed
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

The app bundles large native components per ABI — llama.cpp, plus the on-device toolchain/Termux binaries. A universal APK carrying both `armeabi-v7a` and `arm64-v8a` copies of everything would be prohibitively large. Code On The Go is distributed primarily as a **direct APK download** from the App Dev for All website, not exclusively through Google Play, so we can't rely on Play's automatic per-ABI splitting to slim downloads.

## Decision

Define two **product flavors** on an `abi` dimension — `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) — **centrally** in `composite-builds/build-logic` (`conf/AndroidModuleConf.kt`), applied to every Android module except `:plugin-api`.

Consequences for the build:
- Build tasks are flavor-qualified: `assembleV8Debug`, `assembleV7Release`, etc. **There is no flavorless `assembleDebug`.**
- Native assets (e.g. the llama AAR) are bundled per flavor via the root `build.gradle.kts`; prebuilt assets live under `assets/release/v7/` and `assets/release/v8/`.

## Consequences

**Positive**
- Each APK ships only one ABI's native libraries → much smaller downloads.
- Explicit, centralized control over per-ABI native bundling.

**Negative / costs**
- No flavorless variant; every build/test/release task is doubled into V7/V8.
- Larger build matrix and more release artifacts to produce and track.
- Co-published per-ABI APKs need distinct `versionCode`s (e.g. an ABI-prefixed code) so an arm64 device won't treat the v7 APK as a downgrade.
- A recurring onboarding gotcha: contributors must use `assembleV8Debug` (etc.), not `assembleDebug`.

## Alternatives considered

- **Universal (fat) APK** — rejected: size, given the large per-ABI native payload.
- **Android App Bundle ABI splits** — rejected: our primary distribution is direct APK download, so we can't depend on Play-side splitting; flavors give us split artifacts on every channel.
- **Gradle ABI splits (`splits { abi { } }`).** The standard way to get per-ABI APKs, and it avoids doubling the build/test matrix that a flavor dimension creates. Rejected because splits only slice one build's native libraries by ABI; they cannot bind a *different artifact* per ABI. We need exactly that: the llama native AAR and prebuilt assets differ per ABI (`bundleLlamaV8Assets`, `assets/release/v7|v8/`), which requires the per-flavor source sets/dependencies only product flavors provide.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — Build & Module Configuration.
- [CLAUDE.md](../../CLAUDE.md) — build task commands.
