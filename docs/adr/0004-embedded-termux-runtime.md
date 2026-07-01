# 0004. Embed Termux as the shell & toolchain runtime

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

A self-contained on-device IDE needs a real POSIX shell, a package ecosystem (git, clang, coreutils, and the binaries the build depends on), and an interactive terminal. Building any of that from scratch — shell, package manager, terminal emulator/view — is a multi-year effort and a distraction from the IDE itself.

Termux is a mature, GPL-licensed Android terminal and package environment that already solves this.

## Decision

**Embed Termux** as the shell and toolchain runtime, vendored as modules:

- `:termux:termux-app` — the Termux application/bootstrap layer.
- `:termux:termux-shared` — shared runtime, file/PTY, and package plumbing.
- `:termux:termux-view` / `:termux:termux-emulator` — the terminal view and terminal emulator used in the IDE's terminal UI.

The IDE's build and shell flows run through this environment.

## Consequences

**Positive**
- A proven shell + package ecosystem and a battle-tested terminal emulator, for free.
- License-compatible with our GPLv3 project.

**Negative / costs**
- A large native/runtime surface and a real **security consideration**: Termux executes arbitrary commands, so command-execution sinks must be handled carefully (see [SECURITY.md](../../SECURITY.md)).
- Maintenance: tracking Termux upstream and integrating it with our build orchestration.
- Adds to APK size and the set of native components per ABI ([ADR 0005](0005-per-abi-product-flavors.md)).

## Alternatives considered

- **A custom shell / NDK-only toolchain** — rejected: enormous scope, and we'd reinvent a package ecosystem.
- **Remote/SSH execution** — rejected: violates offline-first.

## Related

- [SECURITY.md](../../SECURITY.md) — command-execution surface.
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — shell module group.
