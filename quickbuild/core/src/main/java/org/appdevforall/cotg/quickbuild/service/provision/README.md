# `service/provision/` - getting a proxy app built, installed, and launched

This folder holds the provisioning side of the service layer: building the Gradle proxy app (first provision and full-rebuild fallback), installing it under the project's real applicationId, launching it, and the clobber check that guards the shared install slot. The `QuickBuildProvisioner` / `ProxyAppLauncher` / `InstalledPackages` interfaces are implemented in the app module (they need Gradle, Context, and PackageManager); everything here stays JVM-testable and depends down on `data/` and `domain/`.

| File | Purpose |
| --- | --- |
| [`QuickBuildProvisioner.kt`](QuickBuildProvisioner.kt) | Interface: the door to Gradle (provision, rebuild, prebuild, cancel), plus the `ProvisionOutcome` / `ProxyAppRebuildOutcome` result types. |
| [`ProxyAppBuildRunner.kt`](ProxyAppBuildRunner.kt) | Runs a provision or rebuild as a stateless verdict - disk guard, build, scratch tree, deploy session, daemon start - returning a result the manager dispatches on. |
| [`ProxyAppInstaller.kt`](ProxyAppInstaller.kt) | Installs the proxy app via CoGo's install pathway, skips when APK bytes already match, and waits on PackageInstaller broadcasts for a real verdict. |
| [`ProxyAppLauncher.kt`](ProxyAppLauncher.kt) | Interface: relaunches the proxy app so a fresh process boots on the newest persisted generation. |
| [`QuickBuildClobberCheck.kt`](QuickBuildClobberCheck.kt) | Stateless check of whether a Quick Build or Standard Run tap would clobber the other build in the shared install slot, keyed on the installed component factory. |
