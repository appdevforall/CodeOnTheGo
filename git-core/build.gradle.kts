plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "com.itsaky.androidide.git.core"
}

dependencies {
	implementation(libs.androidx.core.ktx.v1120)
	implementation(libs.androidx.appcompat.v171)
	implementation(libs.google.material)
	implementation(libs.git.jgit)
	coreLibraryDesugaring(libs.desugar.jdk.libs.v215)
	implementation(libs.common.kotlin.coroutines.core)
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.androidx.security.crypto)

    testImplementation(libs.tests.junit)
    androidTestImplementation(libs.tests.androidx.junit)
    androidTestImplementation(libs.tests.androidx.espresso.core)
}
