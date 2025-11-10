import com.google.protobuf.gradle.id

plugins {
	id("java-library")
	alias(libs.plugins.google.protobuf)
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
	}
	generateProtoTasks {
		all().forEach { task ->
			task.builtins {
				getByName("java") {
					option("lite")
				}
			}
		}
	}
}

dependencies {
	api(libs.google.protobuf.java)
}
