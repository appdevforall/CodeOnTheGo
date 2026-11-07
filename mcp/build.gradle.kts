plugins {
	kotlin("jvm") version "2.4.10"
	application
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
	implementation("io.ktor:ktor-server-cio:3.5.1")

	// The client SSE plugin ships inside ktor-client-core, which arrives via
	// kotlin-sdk-client. There is no separate ktor-client-sse artifact.
	testImplementation(kotlin("test"))
	testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
	testImplementation("io.ktor:ktor-client-cio:3.5.1")
}

// Under flox the running JVM is already 17, so this resolves locally. Outside flox
// it fails with an explicit toolchain error instead of silently building on JDK 21.
kotlin {
	jvmToolchain(17)
}

application {
	mainClass.set("com.itsaky.androidide.mcp.MainKt")
}

tasks.test {
	useJUnitPlatform()
}
