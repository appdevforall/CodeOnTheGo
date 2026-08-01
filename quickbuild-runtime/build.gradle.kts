import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
}

description =
	"Quick Build runtime embedded in generated proxy apps: binds to CoGo, receives payload fds, hot-reloads (ADFA-4128)"

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.quickbuild.runtime"

	defaultConfig {
		// Runs inside apps BUILT WITH CoGo, not inside the IDE.
		minSdk = BuildConfig.MIN_SDK_FOR_APPS_BUILT_WITH_COGO
	}

	compileOptions {
		// Java-only and Java 8, like :logsender - the AAR is injected into user
		// projects and must not drag kotlin-stdlib or any other dependency in.
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	buildFeatures.apply {
		aidl = true
		viewBinding = false
		buildConfig = false
	}
}

// JVM unit tests for the plain-Java payload logic (generation gate, metadata/component
// map parsing, asset extraction). Mirrors :quick-build's jupiter setup.
tasks.withType<Test> {
	useJUnitPlatform()
	// StreamsTest exercises the 256 MB payload cap through the default readFully
	// overload; a capped reader legitimately buffers up to the cap before throwing,
	// which overflows Gradle's default 512 MB test-worker heap.
	maxHeapSize = "1g"
}

// DoD coverage gate: >=90% line+branch on non-UI (domain/data) code.
// Same shape as :quick-build's report: the root build attaches the jacoco agent to
// every Test task, and for Android modules the exec lands at
// build/outputs/unit_test_code_coverage/<variant>UnitTest/, NOT build/jacoco/ -- a
// JacocoReport pointed at build/jacoco/ silently SKIPs and the gate is never
// measured (ADFA-3834 learnings).
tasks.register<JacocoReport>("jacocoTestReport") {
	group = "verification"
	description = "JaCoCo line+branch coverage for the v8Debug unit tests."
	dependsOn("testV8DebugUnitTest")

	reports {
		xml.required.set(true)
		html.required.set(true)
	}

	// Java-only module: the hand-written surface is the javac output. The AIDL stubs
	// (IQuickBuildHost/IQuickBuildTarget + nested Stub/Proxy/Default) are generated
	// code, so they are excluded from the measured set.
	//
	// Device-only Android/binder glue is EXEMPT from the JVM coverage bar (DoD: >=90%
	// line+branch on non-UI code; these classes only execute meaningfully on a device
	// and are covered by the android-qa device walks instead). Anything JVM-testable
	// stays in the measured set - notably ThreeFingerTapDetector, ServiceTracker's
	// census seams, LegacyResourceSwap's file half, and all parsing/persistence code.
	classDirectories.setFrom(
		fileTree(
			layout.buildDirectory.dir("intermediates/javac/v8Debug/compileV8DebugJavaWithJavac/classes"),
		) {
			exclude("com/itsaky/androidide/quickbuild/IQuickBuild*")
			// Binder host service: payload fds, Handler/Looper, activity relaunch orchestration.
			exclude("com/itsaky/androidide/quickbuild/runtime/QuickBuildRuntime*")
			// ServiceConnection bind/reconnect to CoGo; binder death + rebind only happen on-device.
			exclude("com/itsaky/androidide/quickbuild/runtime/QuickBuildClient*")
			// Framework-instantiated AppComponentFactory (Activity/Service/Provider hooks).
			exclude("com/itsaky/androidide/quickbuild/runtime/QuickBuildAppComponentFactory*")
			// InMemoryDexClassLoader (ART-only) + /proc + android.os.Process boot path; not
			// splittable without moving prod code around - the generation-gate logic it defers
			// to (Generations, PayloadPersistence) is JVM-tested.
			exclude("com/itsaky/androidide/quickbuild/runtime/PayloadStore*")
			// API 30+ ResourcesLoader/ResourcesProvider attach; framework Resources objects only.
			exclude("com/itsaky/androidide/quickbuild/runtime/ResourceStore*")
			// Overlay banner View/TextView UI (UI is DoD-exempt; OverlayState text model is JVM-tested).
			exclude("com/itsaky/androidide/quickbuild/runtime/StatusOverlay*")
			// Overlay button View UI + WindowInsets (UI is DoD-exempt).
			exclude("com/itsaky/androidide/quickbuild/runtime/ReturnToIdeButton*")
			// Application.ActivityLifecycleCallbacks census over real Activity instances.
			exclude("com/itsaky/androidide/quickbuild/runtime/ActivityTracker*")
			// Activity/MotionEvent dispatch + PackageManager launch glue; the burst state
			// machine it feeds (ThreeFingerTapDetector) is JVM-tested and stays measured.
			exclude("com/itsaky/androidide/quickbuild/runtime/QuickBuildGestures*")
			// Intent trampoline to CoGo's jump activity (startActivity glue).
			exclude("com/itsaky/androidide/quickbuild/runtime/JumpToEditor*")
		},
	)
	sourceDirectories.setFrom(files("src/main/java"))
	executionData.setFrom(
		layout.buildDirectory.file(
			"outputs/unit_test_code_coverage/v8DebugUnitTest/testV8DebugUnitTest.exec",
		),
	)
}

dependencies {
	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testRuntimeOnly(libs.tests.junit.platformLauncher)
}
