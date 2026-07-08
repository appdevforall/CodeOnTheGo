# Gradle payload harness (ADFA-4128 phase 4)

Real AGP-built payloads so androidx/Material3/Kotlin/Compose get proper resource
merging + dexing — the apps CoGo actually emits. The key trick that makes the
whole shell approach work with real dependency trees:

    android { androidResources { additionalParameters +=
        listOf("--package-id", "0x80", "--allow-reserved-package-id") } }

AGP links the app AND all its library resources (appcompat, material, …) into a
single resource table at package id 0x80 — disjoint from the host shell's 0x7f —
so `ResourcesProvider.loadFromApk` merges it into the Activity without collision.
Verified: `aapt2 dump packagename` → `id=80`.

Build:  ./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=<sdk>/build-tools/35.0.0/aapt2
Deploy: sh ../tools/deploy_payload.sh app/build/outputs/apk/debug/app-debug.apk

Swap `app/src/main` per variant (material3 / kotlin / compose / fragments).
