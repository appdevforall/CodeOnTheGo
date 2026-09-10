# `domain/session/` - the session state machine

Pure-JVM state machine for a quick-build session: its states, the events that drive them, the effects the shell must run, and what the user is told. No Android. `SessionReducer.reduce` is total and exhaustive - every (state, event) pair is listed, and the ones a state ignores keep the state and emit no effects, so a late or duplicate event can never corrupt the session, and a new event fails to compile until every state says what it does with it. The user's ask (a tap meaning "bring the proxy app forward once my changes are in it") has one home, `PendingAsk`: the reducer records and withdraws it through `RecordAsk` / `WithdrawAsk` effects and reads it through `reduce`'s `askOutstanding` argument. `QuickBuildStatus` and `QuickBuildTone` derive purely from state, so a stuck banner or a wrong icon color is unrepresentable.

| File | Purpose |
| --- | --- |
| [`SessionReducer.kt`](SessionReducer.kt) | The total transition function: maps (state, event) to next state plus ordered effects. |
| [`QuickBuildSessionState.kt`](QuickBuildSessionState.kt) | The state sealed type plus `SessionFailure`, `SessionEvent`, `SessionEffect`, and `SessionTransition`. |
| [`PendingAsk.kt`](PendingAsk.kt) | The one record of a tap that still owes the switch to the proxy app; owned by the session manager, answered when the app is brought forward. |
| [`QuickBuildStatus.kt`](QuickBuildStatus.kt) | The status surface derived from state via `from(state)`. |
| [`QuickBuildTone.kt`](QuickBuildTone.kt) | The colorblind-safe toolbar tone derived from status via `toTone()`. |
| [`QuickBuildNotice.kt`](QuickBuildNotice.kt) | Enum of host-shown notices (named, not written, since this module has no `R`), each carrying its own tone. |
| [`QuickBuildMessage.kt`](QuickBuildMessage.kt) | Sealed type of named failure messages the host maps to string resources; `Literal` passes final text through. |

## State machine

This is the authoritative rendering: every transition with a guard, drawn in full. The copies in [quickbuild/README.md](../../../../../../../../../../README.md) and [docs/pipeline.md](../../../../../../../../../../docs/pipeline.md) are deliberately simplified for orientation.

Arrows are labeled with the `SessionEvent` that drives them; parentheticals note the guard or a key effect. Self-loops that only run an effect (a tap that triggers a live reload, a retry that kicks off a rebuild) are shown; pure no-ops are not.

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> Provisioning: QuickBuildTapped (RecordAsk)
    Idle --> Prebuilding: PrebuildRequested
    Idle --> Idle: FileSaved (clears lastStartFailed)

    Prebuilding --> Prebuilding: QuickBuildTapped (queue the tap; RecordAsk)
    Prebuilding --> Prebuilding: FileSaved (clears lastStartFailed)
    Prebuilding --> Provisioning: PrebuildFinished (tap queued)
    Prebuilding --> Idle: PrebuildFinished (no tap)
    Prebuilding --> Idle: CancelRequested (tap queued)

    Provisioning --> Ready: ProvisioningSucceeded (SwitchToProxyApp if the ask is outstanding)
    Provisioning --> Provisioning: QuickBuildTapped (RecordAsk)
    Provisioning --> Idle: ProvisioningFailed
    Provisioning --> Idle: CancelRequested (no rebaseline)
    Provisioning --> Provisioning: CancelRequested (rebaseline - WithdrawAsk + CancelProxyAppRebuild)
    Provisioning --> Invalidated: ProxyAppRebuildFailed (awaitingRetry)
    Provisioning --> Invalidated: ProxyAppRebuildInstallNotConfirmed
    Provisioning --> Invalidated: ProxyAppRebuildDeferred

    Ready --> Ready: QuickBuildTapped (RecordAsk + TriggerLiveReload)
    Ready --> Building: BuildStarted
    Ready --> Building: WarmCompileStarted
    Ready --> Invalidated: InvalidationDetected
    Ready --> Degraded: DaemonDied
    Ready --> Ready: ProxyAppCrashed (record failure)
    Ready --> Ready: ExternalBuildCompleted (RefreshBaseline)

    Building --> Deployed: BuildSucceeded (SwitchToProxyApp if the ask is outstanding)
    Building --> Ready: BuildFailed
    Building --> Ready: CancelRequested (not warming)
    Building --> Ready: WarmCompileFinished
    Building --> Building: QuickBuildTapped (RecordAsk; warming - TriggerLiveReload; real build - MarkBuildUserInitiated)
    Building --> Building: ProxyAppCrashed (warming - carry as pendingCrash)
    Building --> Building: ExternalBuildCompleted (RefreshBaseline)
    Building --> Invalidated: InvalidationDetected
    Building --> Degraded: DaemonDied

    Deployed --> Deployed: QuickBuildTapped (RecordAsk + TriggerLiveReload)
    Deployed --> Building: BuildStarted
    Deployed --> Building: WarmCompileStarted
    Deployed --> Invalidated: InvalidationDetected
    Deployed --> Degraded: DaemonDied
    Deployed --> Ready: ProxyAppCrashed (record failure)
    Deployed --> Deployed: ExternalBuildCompleted (RefreshBaseline)

    Invalidated --> Provisioning: ProxyAppRebuildStarted (carries the reason as rebaselineReason)
    Invalidated --> Invalidated: QuickBuildTapped (RecordAsk; awaiting retry - RunProxyAppRebuild)
    Invalidated --> Invalidated: HostForegrounded retry (RunProxyAppRebuild)
    Invalidated --> Invalidated: InvalidationDetected (awaiting retry - re-park + RunProxyAppRebuild)
    Invalidated --> Building: BuildStarted (awaiting retry)
    Invalidated --> Deployed: BuildSucceeded (awaiting retry; SwitchToProxyApp if the ask is outstanding)
    Invalidated --> Ready: BuildFailed (awaiting retry)
    Invalidated --> Invalidated: DaemonDied (awaiting retry, RespawnDaemon)

    Degraded --> Ready: DaemonRespawned (not restartFailed)
    Degraded --> Degraded: DaemonRespawned (restartFailed - the announced daemon already died)
    Degraded --> Degraded: DaemonDied / DaemonRestartFailed (restartFailed = true, no auto-retry)
    Degraded --> Invalidated: InvalidationDetected
    Degraded --> Degraded: ExternalBuildCompleted (RefreshBaseline)
    Degraded --> Degraded: QuickBuildTapped (RecordAsk; restartFailed - SurfaceMessage + RespawnDaemon; else ack only)
    Degraded --> Building: BuildStarted
    Degraded --> Deployed: BuildSucceeded (SwitchToProxyApp if the ask is outstanding)
    Degraded --> Ready: BuildFailed

    Idle --> Idle: SessionRestartRequested (lastStartFailed - clears the failed-start tone)

    note right of Idle
        SessionRestartRequested from any
        state -> Idle (TeardownSession).
        SessionRestartAndReprovisionRequested from any
        state -> Provisioning (TeardownAndProvision;
        StartProvisioning from Idle), with RecordAsk when
        the event is userInitiated - the menu and dialog,
        not a variant-switch reprovision. Every stop, park
        and teardown emits WithdrawAsk first.
    end note
```

The reducer is total: any (state, event) pair not drawn above is listed as ignored in its state's `when` and keeps the current state with no effects.
