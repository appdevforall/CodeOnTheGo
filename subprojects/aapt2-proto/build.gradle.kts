import com.itsaky.androidide.plugins.conf.configureProtoc

plugins {
	id("java-library")
	alias(libs.plugins.google.protobuf)
}

configureProtoc(protobuf = protobuf, protocVersion = libs.versions.protobuf.asProvider())

protobuf {
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
