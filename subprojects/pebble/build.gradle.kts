// Pebble 4.1.1, built from source so its lexer can be fixed for Android; see NOTICE.
plugins {
	id("java-library")
}

dependencies {
	// The versions io.pebbletemplates:pebble:4.1.1 resolved to before it was vendored.
	implementation(libs.unbescape)
	implementation(libs.tooling.slf4j)

	testImplementation(libs.tests.junit)
}
