# PR Quick-Buildability Survey

_Generated 2026-07-22T07:43:34Z. Classification mirrors `ChangeClassifier.kt` (path-based, exact)._

**Quick Build would handle 1104 of 2927 recent merged PRs (37.7%) across 100 active Android apps on its fast path; the rest need a full Gradle build.**

## Overall

| Metric | Value |
|---|---|
| Repos surveyed | 100 |
| Merged PRs classified | 2927 |
| Quick-buildable | 1104 (37.7%) |
| Quick-buildable, PRs <= 20 files | 40.0% (2542 PRs) |
| Quick-buildable, PRs > 20 files | 22.3% (385 PRs) |

## What-if: a proposed ignore-list for non-buildable files

> **37.7% today -> 56.4% with the proposed ignore-list (+546 PRs flip from full Gradle build to a quick route; PROPOSED-SEMANTICS ESTIMATE, conservative lower bound).**

_Proposed-semantics estimate from cached PR summaries. The real ChangeClassifier has NO ignore-list today. Conservative lower bound: extensionless files (LICENSE) and non-yaml .github/ files are not counted as ignorable in the summary estimate._

Proposed ignore-list (files that would NOT invalidate a quick build):

- Docs: *.md, *.markdown, *.txt, *.rst, *.adoc, and anything under a `docs/` dir
- CI/VCS/editor: *.yml, *.yaml, anything under `.github/`, .gitignore, .gitattributes, .editorconfig
- Project metadata: LICENSE/NOTICE/COPYING/CODEOWNERS, anything under `fastlane/`
- Scope: only OUTSIDE a module `src/` dir -- files under src/ are always build inputs

| Metric | Value |
|---|---|
| Quick-buildable today | 1104 (37.7%) |
| Quick-buildable with ignore-list | 1650 (56.4%) |
| PRs that flip (full Gradle -> quick) | 546 |

Residual routes of the flipped PRs (what they'd build instead):

| Residual route | PRs |
|---|---|
| NoOp | 366 |
| CodeOnly | 98 |
| ResourcesOnly | 64 |
| CodeAndResources | 18 |

## Route distribution

| Route | PRs | Quick? |
|---|---|---|
| FullGradleBuild | 1823 | no |
| CodeOnly | 695 | yes |
| ResourcesOnly | 272 | yes |
| CodeAndResources | 131 | yes |
| NoOp | 4 | yes |
| AssetsOnly | 2 | yes |

## Top fallback causes (PRs forced to full Gradle build)

| Cause (triggering file/rule) | PRs |
|---|---|
| non-source .md | 501 |
| version catalog (libs.versions.toml) | 482 |
| non-source .yml | 332 |
| build.gradle.kts | 281 |
| build.gradle | 144 |
| AndroidManifest.xml | 133 |
| non-source .json | 117 |
| non-source .xml | 108 |
| non-source .txt | 102 |
| non-source (no ext) | 84 |

## Per-repo

| Repo | PRs | Quick-buildable | % |
|---|---|---|---|
| ZemerTeam/zemer-cipher | 1 | 1 | 100% |
| Hamza417/Inure | 50 | 45 | 90% |
| sdex/ActivityManager | 50 | 45 | 90% |
| AntennaPod/AntennaPod | 50 | 40 | 80% |
| Ashinch/ReadYou | 50 | 39 | 78% |
| Hy4ri/hermes-mobile | 50 | 38 | 76% |
| uakihir0/planetlink | 50 | 38 | 76% |
| ankidroid/Anki-Android | 50 | 36 | 72% |
| gsantner/markor | 50 | 36 | 72% |
| Neamar/KISS | 50 | 34 | 68% |
| N-Zik-Group/N-Zik | 50 | 33 | 66% |
| Hamza417/Felicity | 38 | 24 | 63% |
| d0x-dev/AirBeats | 8 | 5 | 62% |
| streetcomplete/StreetComplete | 50 | 31 | 62% |
| MixinNetwork/android-app | 50 | 30 | 60% |
| OneBusAway/onebusaway-android | 50 | 30 | 60% |
| bitfireAT/davx5-ose | 50 | 30 | 60% |
| duckduckgo/Android | 50 | 30 | 60% |
| JunkFood02/Seal | 50 | 26 | 52% |
| androidx/androidx | 6 | 3 | 50% |
| garfiec/Librechat-Mobile | 50 | 25 | 50% |
| open-ani/animeko | 50 | 25 | 50% |
| pewaru-333/HomeMedkit-App | 50 | 25 | 50% |
| shiaho777/web-to-app | 50 | 25 | 50% |
| Ivorisnoob/Koda | 35 | 17 | 49% |
| tzebrowski/ObdGraphs | 50 | 24 | 48% |
| Rosemoe/sora-editor | 50 | 23 | 46% |
| nextcloud/android | 50 | 23 | 46% |
| wordpress-mobile/WordPress-Android | 50 | 23 | 46% |
| Adyen/adyen-android | 50 | 21 | 42% |
| zly2006/zhihu-plus-plus | 50 | 21 | 42% |
| home-assistant/android | 50 | 19 | 38% |
| signalapp/Signal-Android | 50 | 19 | 38% |
| Tweener/czan | 8 | 3 | 38% |
| composablehorizons/compose-unstyled | 50 | 17 | 34% |
| vrcm-team/VRCM | 9 | 3 | 33% |
| Aryan-Raj3112/episteme | 50 | 16 | 32% |
| SRGSSR/pillarbox-android | 50 | 16 | 32% |
| jeiel85/markleaf-android | 33 | 10 | 30% |
| SecUSo/privacy-friendly-qr-scanner | 50 | 15 | 30% |
| ZemerTeam/zemer-app | 50 | 15 | 30% |
| android/architecture-samples | 50 | 15 | 30% |
| SecUSo/privacy-friendly-notes | 50 | 14 | 28% |
| TeamNewPipe/NewPipe | 50 | 13 | 26% |
| Sreecharannuthi/kordx | 4 | 1 | 25% |
| joreilly/Confetti | 50 | 12 | 24% |
| yschimke/compose-ai-tools | 50 | 12 | 24% |
| bloomberg/pushiko | 50 | 11 | 22% |
| SecUSo/privacy-friendly-todo-list | 50 | 10 | 20% |
| seijikohara/femto-car-launcher | 50 | 10 | 20% |
| trevarj/motd | 5 | 1 | 20% |
| vinaygaba/Learn-Jetpack-Compose-By-Example | 50 | 6 | 12% |
| boxcreate/boxlore | 50 | 5 | 10% |
| wgtunnel/android | 50 | 5 | 10% |
| LawnchairLauncher/lawnicons | 50 | 3 | 6% |
| getsentry/sentry-java | 50 | 3 | 6% |
| jarnedemeulemeester/findroid | 50 | 3 | 6% |
| PostHog/posthog-android | 50 | 1 | 2% |
| 100nandoo/shelfdroid | 50 | 0 | 0% |
| AbubakarMahmood/Echoes | 0 | 0 | 0% |
| AgusRomeroL/video-boost-ao | 0 | 0 | 0% |
| Arnav-Sharma-codes/FinX-Android | 0 | 0 | 0% |
| BlackF1re/watchmetrics | 0 | 0 | 0% |
| BugeStudioTeam/Buge-App-Manager | 2 | 0 | 0% |
| Dvalin21/UnifiedComms | 0 | 0 | 0% |
| Evilgodxu/EdgeGesture | 0 | 0 | 0% |
| GateOfTruth/BluetoothBleX | 0 | 0 | 0% |
| Helandy/YummyTV | 2 | 0 | 0% |
| Kaifazad/LocalShare | 0 | 0 | 0% |
| MesmerPrism/Rusty-Kiosk | 5 | 0 | 0% |
| MrX-zeta/Itera | 0 | 0 | 0% |
| QWEA0/Liquid-Glass-Android | 0 | 0 | 0% |
| SirPrizeNZ/keen | 0 | 0 | 0% |
| SysAdminDoc/CallShield | 0 | 0 | 0% |
| Tweener/passage | 2 | 0 | 0% |
| VarunKumarMup/SpaceClean | 0 | 0 | 0% |
| akasajal/roadRoot | 0 | 0 | 0% |
| aoreshkov/kmp-ledger | 9 | 0 | 0% |
| arkaceananda/NewYearCountdown | 0 | 0 | 0% |
| bgorzelic/signal-app | 0 | 0 | 0% |
| chrisbanes/tivi | 50 | 0 | 0% |
| edwardlthompson/screen-wakelock-detector | 0 | 0 | 0% |
| envywook/Lust | 0 | 0 | 0% |
| formatools/forma | 50 | 0 | 0% |
| iZakirSheikh/OnePlayer | 0 | 0 | 0% |
| jrs8205/BetaScout | 0 | 0 | 0% |
| killertop/NekoPilot-Android | 0 | 0 | 0% |
| klllas/vokta | 0 | 0 | 0% |
| lebrit/qwdtt-legacy-android | 0 | 0 | 0% |
| lydavid/MusicSearch | 50 | 0 | 0% |
| mozilla-mobile/reference-browser | 50 | 0 | 0% |
| pawelorzech/FastMask | 3 | 0 | 0% |
| po4yka/RIPDPI | 50 | 0 | 0% |
| priv-kit/priv-kit | 0 | 0 | 0% |
| raikwaramit/CryptoAtlas | 6 | 0 | 0% |
| siddharthjaswal/logpose | 1 | 0 | 0% |
| skaradimitriou/elmepa-uni-app | 50 | 0 | 0% |
| sm32d/streamfolio | 0 | 0 | 0% |
| tjdgus278/ProConKit | 0 | 0 | 0% |
| vauchi/android | 0 | 0 | 0% |
