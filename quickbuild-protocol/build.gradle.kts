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
