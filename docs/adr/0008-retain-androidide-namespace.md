# 0008. Retain the `com.itsaky.androidide` namespace after rebrand

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

Code On The Go is the rebranded successor to **AndroidIDE**. The product name, branding, and assets changed, but the inherited codebase carries the original identity deeply: the application id and Gradle namespace are `com.itsaky.androidide` (`BuildConfig.PACKAGE_NAME`), `rootProject.name` is `AndroidIDE`, plus many thousands of references, the generated `R` class, the manifest, package-qualified vendored substitutions, signing identity, and existing installs in the field.

Changing an Android **application id** breaks the update path for installed users (a different app id is a different app) and disrupts signing/identity continuity. A rename of this size also ripples through the vendored `com.itsaky.androidide.build:*` substitutions ([ADR 0003](0003-vendored-forked-desktop-toolchain.md)).

## Decision

**Keep** the `com.itsaky.androidide` application id / namespace and the internal Gradle project names. Rebranding (`INTERNAL_NAME = "CodeOnTheGo"`, the app label, icons, and user-facing assets) is applied at the **presentation layer only**, not the package identity.

## Consequences

**Positive**
- Preserves the update path and signing continuity for existing installs.
- Avoids a massive, high-risk refactor and the churn it would cause across vendored substitutions and the `R` class.

**Negative / costs**
- The codebase namespace doesn't match the product name, confusing to newcomers. [CLAUDE.md](../../CLAUDE.md) and [ARCHITECTURE.md](../../ARCHITECTURE.md) call this out so the mismatch is expected, not surprising.
- Care is needed to ensure user-facing strings/branding are overridden and no "AndroidIDE" naming leaks to users.

## Alternatives considered

- **Full rename to `org.appdevforall.*`** — rejected: breaks updates for existing users, requires enormous error-prone churn, and risks destabilizing the vendored substitutions for little gain.
- **Partial rename** (some modules) — rejected: produces an inconsistent namespace with the same risks and no clean payoff.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — overview note on the namespace/product-name mismatch.
- [ADR 0003](0003-vendored-forked-desktop-toolchain.md) — vendored coordinates that a rename would disrupt.
