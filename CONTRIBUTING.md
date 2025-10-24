# Contributing

This document outlines how to propose a change to Code On The Go. By contributing to this project, you
agree to abide the terms specified in the [CODE OF CONDUCT](./CODE_OF_CONDUCT.md).

## Requirements

- Android Studio.
- JDK 17

## Git Pre-Commit Hook: Branch Name Enforcement

This project enforces a strict branch naming policy using a Git pre-commit hook.

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
````

#### Windows
```bash
scripts\install-git-hooks.bat
````
## Source code format

- Indents : 2-space
- Java : `GoogleStyle`. Either use `google-java-format` or
  import [this](https://raw.githubusercontent.com/google/styleguide/gh-pages/intellij-java-google-style.xml)
  code style.
- Kotlin: Use [`ktfmt`](https://plugins.jetbrains.com/plugin/14912-ktfmt) IntelliJ Plugin and set
  the code style to `Google (internal)`
  . [`Learn more`](https://github.com/facebook/ktfmt#intellij-android-studio-and-other-jetbrains-ides)
  .
- XML : Default Android Studio formatter with 2-space indentations.

## Propose a change

Before proposing a change, please open an issue and discuss it with our team.

**The `dev` branch is protected and requires all commits to be signed with your GPG key and the
commit history to be linear.**
See [protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/defining-the-mergeability-of-pull-requests/about-protected-branches)
.

To contribute to this project,

- Fork the repo.
- Clone the forked repo to your local machine.
- Open the project.
- Make your changes.
- Create a pull request that includes a meaningful title and description.

## Report issues 

[Report issues and request features here](https://github.com/appdevforall/CodeOnTheGo/issues).

## Contact us

- [Website](https://www.appdevforall.org)
- [Official Telegram channel](https://t.me/CodeOnTheGoOfficial)
- [Telegram discussions](https://t.me/CodeOnTheGoDiscussions)
- [Email](mailto:feedback@appdevforall.org)









