# Architecture Decision Records

This directory holds **Architecture Decision Records (ADRs)** — short documents capturing a significant architectural decision, its context, and its consequences. They explain *why* the codebase is the way it is, so a decision isn't silently undone later.

Format is lightweight **MADR / Nygard**: Context → Decision → Consequences → Alternatives. Most records here are *retroactive*, documenting decisions already embedded in the code.

## Conventions

- One decision per file, named `NNNN-kebab-title.md` with a zero-padded sequence number.
- **Status** lifecycle: `Proposed` → `Accepted` → `Superseded by NNNN` / `Deprecated`. Don't edit a decision after it's accepted; write a new ADR that supersedes it.
- Keep it short and concrete. Link related ADRs and relevant code paths.

## Index

| # | Decision | Status |
|---|---|---|
| [0001](0001-prefer-room-for-persistence.md) | Prefer Room for persistence; raw SQLite only for justified exceptions | Proposed |
| [0002](0002-on-device-builds-via-gradle-tooling-api.md) | On-device builds run real Gradle out-of-process via the Tooling API | Proposed |
| [0003](0003-vendored-forked-desktop-toolchain.md) | Vendor & fork the desktop toolchain via composite-build substitution | Proposed |
| [0004](0004-embedded-termux-runtime.md) | Embed Termux as the shell & toolchain runtime | Proposed |
| [0005](0005-per-abi-product-flavors.md) | Ship per-ABI product flavors (v7/v8), not a universal APK | Proposed |
| [0006](0006-koin-dependency-injection.md) | Use Koin for dependency injection, not Hilt/Dagger | Proposed |
| [0007](0007-strictmode-whitelist-engine.md) | Enforce StrictMode via a custom whitelist engine | Proposed |
| [0008](0008-retain-androidide-namespace.md) | Retain the `com.itsaky.androidide` namespace after rebrand | Proposed |
| [0009](0009-jetpack-compose-for-new-ui.md) | Build new UI in Jetpack Compose, not XML Views | Proposed |
