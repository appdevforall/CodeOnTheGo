# Contributing

This document outlines how to propose a change to Code On The Go. By contributing to this project, you
agree to abide the terms specified in the [CODE OF CONDUCT](./CODE_OF_CONDUCT.md).

## Requirements

- Android Studio.
- JDK 17

## Git Pre-Commit Hook: Branch Name Enforcement

This project enforces a strict branch naming policy using a Git pre-commit hook. These formats
are for core-team contributions that reference an internal ticket. If you are a community
contributor without access to that ticket system, use the `community/` prefix instead — see
[Community contributions](#community-contributions).

### Allowed Branch Formats:
- `ADFA-123` (3 to 5 digit number)
- `feature/ADFA-123`
- `bugfix/ADFA-12345`
- `chore/ADFA-9999`
- `anyprefix/ADFA-#####`

### Setup

#### Mac/Linux:
```bash
sh ./scripts/install-git-hooks.sh
```

#### Windows
```bash
scripts\install-git-hooks.bat
```
## Source code format

Formatting is enforced by **Spotless** (`ratchetFrom = origin/stage`, so only changed lines are
checked). Run `./gradlew spotlessApply` before pushing; CI fails on unformatted changed lines.

- **Indentation:** tabs. **Line endings:** LF.
- **Java:** the Eclipse formatter (config `spotless.eclipse-java.xml`), with member sorting and import ordering.
- **Kotlin** and `*.gradle.kts`: `ktlint`.
- **XML:** the Eclipse WTP formatter (config `spotless.eclipse-xml.prefs`).

## Propose a change

Before proposing a change, please open an issue and discuss it with our team.

**Branch model.** `stage` is the protected default/integration branch; `main` is the release
branch. Base feature branches on `stage` and open pull requests back into `stage` — never branch
from `main` or target it directly. `main` only ever receives merges from `stage`, for releases.
See [protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/defining-the-mergeability-of-pull-requests/about-protected-branches)
.

To contribute to this project,

- Fork the repo.
- Clone the forked repo to your local machine.
- Open the project.
- Make your changes.
- Create a pull request that includes a meaningful title and description.

## Community contributions

External contributors don't have access to our internal ticket system, so the `ADFA-` branch
naming and ticket validation used by the core team don't apply to you.

- After forking (see [Propose a change](#propose-a-change)), name your branch with the
  `community/` prefix followed by a short, descriptive summary — for example
  `community/fix-editor-crash`.
- Do **not** use the `ADFA-` prefix. It is reserved for internal branches linked to a ticket.
  When a pull request from a fork uses an `ADFA-` branch name, the **Lint Branch Name** check
  comments on the pull request and fails until you rename the branch to start with `community/`.

The branch-name pre-commit hook above enforces the `ADFA-` format, so it is meant for the core
team — community contributors don't need to install it. On `community/` branches, our build
pipeline automatically skips the ticket-specific steps and runs the rest of the checks as usual.

## Report issues 

[Report issues and request features here](https://github.com/appdevforall/CodeOnTheGo/issues).

## Contact us

- [Website](https://www.appdevforall.org)
- [Official Telegram channel](https://t.me/CodeOnTheGoOfficial)
- [Telegram discussions](https://t.me/CodeOnTheGoDiscussions)
- [Email](mailto:feedback@appdevforall.org)










