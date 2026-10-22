# 0019. VS Code extensions are ported, not hosted

- **Status:** Proposed
- **Date:** 2026-10-13
- **Deciders:** Code On The Go team

## Context

ADFA-96 asks whether VS Code extensions matter to Android developers and CS students, and whether CoGo can support them.

They matter to students: VS Code is the most-used IDE in Stack Overflow's 2025 Developer Survey. Android-specific tooling lives in Android Studio plugins instead, which [ADR 0018](0018-android-studio-plugins-are-ported-not-run.md) covers.

A VS Code extension has two halves. Its `package.json` declares contributions - languages, TextMate grammars, snippets, themes, commands, menus, views, settings, debuggers. Its JavaScript runs in the extension host, a separate Node.js process, against the `vscode` API.

Running extensions unchanged needs both halves on the device, and neither is there:

- **No Node.js.** CoGo's Termux bootstrap does not include it, and CoGo's package repository (`packages.appdevforall.org`, 324 arm64 packages as of 2026-08-03) does not carry `nodejs`, `npm` or `code-server`. It does carry OpenJDK 21, Python 3.12 and clang.
- **The `vscode` API would have to be re-implemented** over CoGo's editor, UI and build. Eclipse Theia does exactly this, and still trails each VS Code release by about a month.
- **Native binaries.** Extensions that bundle a language server or tool ship desktop builds. code-server, Coder's build of VS Code that runs as a server, installs in Termux, but its documentation reports that many extensions fail to install because Android is not a supported platform.
- **Distribution.** The Visual Studio Marketplace's terms limit its extensions to Microsoft products, which leaves Open VSX - a smaller catalogue - as the only usable registry.

## Decision

**CoGo does not host the VS Code extension API.** An extension's behavior reaches CoGo by being ported to the CoGo plugin API - see [Porting a VS Code extension](../PLUGIN_AUTHORING.md#porting-a-vs-code-extension) - reusing the open standards it is built on:

- **Language servers**, which many language extensions wrap, run through `LanguageExtension` unchanged, provided the server runs on the device: a native Termux build, or a server on the bundled Java or Python runtime.
- **Snippets** use the same body syntax as CoGo's; only the file layout differs.

TextMate grammars and colour themes could be imported later, each as its own ticket. CoGo highlights with tree-sitter, and sora's TextMate support is in the version catalog but unused; themes are keyed to TextMate scopes, so mapping them to tree-sitter captures loses detail.

## Consequences

**Positive**

- No Node.js runtime, extension host, or `vscode` API to ship and keep in step with Microsoft's releases.
- Language support carries over wherever an extension gets it from a language server.

**Negative**

- No VS Code extension works unmodified. Webviews, custom views and commands need porting to the plugin API.
- Language servers written for Node.js do not run until the package repository carries Node.js.
- Debugger extensions do not port: CoGo's debugger does not speak the Debug Adapter Protocol.

## Alternatives considered

- **A VS Code extension host in CoGo** - Node.js plus a `vscode` API over CoGo's services. Rejected: Theia-scale work, tracked against every VS Code release, and it still would not run extensions whose native binaries have no Android build.
- **Embed code-server in a WebView** - rejected: it runs a second editor, extension host and set of language servers beside CoGo's own, none of them connected to CoGo's editor or build, and it needs Node.js the package repository does not have.

## Related

- [ADR 0018](0018-android-studio-plugins-are-ported-not-run.md) - the same decision for Android Studio plugins.
- [ADR 0004](0004-embedded-termux-runtime.md) - the Termux runtime and package repository a language server runs on.
- [`docs/plugin-api.md`](../plugin-api.md) - the plugin API contract a port targets.
