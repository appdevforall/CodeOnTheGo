# `service/deploy/` - the AIDL channel to the running proxy app

This folder holds the deploy side of the service layer: the exported host service the proxy app binds to, the uid-gated connection registry, the channel that sends build payloads (dex/arsc/assets as fds) over AIDL and awaits a verdict, and the deployer that routes each build to hot swap or process restart. `QuickBuildHostService` is Android-instantiated and meets the session pipeline through the process-wide `ProxyAppConnections.INSTANCE`; the rest depends down on `data/` and `domain/`.

| File | Purpose |
| --- | --- |
| [`QuickBuildHostService.kt`](QuickBuildHostService.kt) | Exported CoGo-side `Service`; the proxy app binds and registers its callback, and every inbound call is uid-gated against the session's expected proxy app. |
| [`ProxyAppConnections.kt`](ProxyAppConnections.kt) | Registry shared between the binder and the session pipeline: the bound target, the accepted uid/package, and the report flow. |
| [`DeployChannel.kt`](DeployChannel.kt) | The on-device `DeploySender`: passes payload files as read-only fds over the oneway `onPayload`, awaits the matching report, and bounds every wait. |
| [`PayloadDeployer.kt`](PayloadDeployer.kt) | Routes a build's artifacts to hot swap vs process restart, handles relaunch/reconnect and the no-app retry, allocates generations, and maps each `DeployResult` to a `BuildOutcome`. |
| [`BuildStatusJson.kt`](BuildStatusJson.kt) | Builds the string-valued `statusJson` for `onBuildStatus` (building, build_ok, build_failed, reinstall_pending) the proxy app's overlay reads. |
