plugins {
	id("java-library")
	id("org.jetbrains.kotlin.jvm")
}

description =
	"Quick Build daemon wire-protocol model: the request/response DTOs and protocol constants shared by the daemon and CoGo's client (ADFA-4128)"

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
	jvmToolchain(17)
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// DoD coverage gate: >=90% line+branch on non-UI (domain/data) code.
// Same wiring as :quickbuild-daemon: the root-applied jacoco plugin auto-creates
// jacocoTestReport for JVM modules with the XML report off and no test dependency;
// the exec lands at the JVM default build/jacoco/test.exec.
tasks.named<JacocoReport>("jacocoTestReport") {
	dependsOn(tasks.test)
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}

dependencies {
	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testRuntimeOnly(libs.tests.junit.platformLauncher)
}
