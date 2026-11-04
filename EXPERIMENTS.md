# Experiments

Features that aren't ready for every user ship behind one switch: the empty file `CodeOnTheGo.exp` in the device's `Download/` folder. Without it, CoGo behaves as released.

## Turning experiments on

```bash
adb shell touch /storage/emulated/0/Download/CodeOnTheGo.exp   # on
adb shell rm /storage/emulated/0/Download/CodeOnTheGo.exp      # off
```

Flag files are read once per process, so force-stop CoGo after creating or deleting one. Reopening it from Recents keeps the old value.

## Seeing what's on

With experiments on, the homepage title reads "Code on the Go." with a trailing dot. Tap the title to list every experiment with its Jira ticket.

## The list: `Experiment`

[`Experiment.kt`](common/src/main/java/com/itsaky/androidide/utils/Experiment.kt) holds one entry per experimental feature. That enum is the complete list.

| Field | Meaning |
|---|---|
| `label` | What the user sees in the list. |
| `jiraKey` | The open ticket that decides whether the feature graduates or goes. |

## Adding an experiment

1. Open a "Graduate or remove experiment: <feature>" ticket.
2. Add an entry: `MY_FEATURE("My feature", "ADFA-1234")`.
3. Gate the code with `Experiments.isOn(Experiment.MY_FEATURE)`.

Never read the flag directly. `FeatureFlags.isExperimentsEnabled` is `internal` to `:common`, so other modules can't. `Experiments.enabled` is the overall switch, and only permanent consumers of the switch itself use it:

- the title dot
- `IdeFeatureFlagService`
- zip templates that mark themselves `experimental`

## Graduating or removing an experiment

Delete its entry. The compiler then flags every remaining gate.

- **Graduate:** at each gate, keep the "on" branch and delete the "off" branch.
- **Remove:** delete the feature along with its gates.

Do it in one PR, then close the ticket.

## Nightly stale-experiment report

[`stale-experiments.yml`](.github/workflows/stale-experiments.yml) runs nightly and on demand. It reads each `jiraKey` from `Experiment.kt`, and in the run summary it lists every experiment whose ticket is Done: the decision is made, but the gate is still in the code. It only reports, so it never fails a build.

## Plugins

Plugins see only the overall switch, through `IdeFeatureFlagService.isExperimentsEnabled()`. Individual experiments aren't exposed: a plugin that depended on an entry would break when the entry is deleted.
