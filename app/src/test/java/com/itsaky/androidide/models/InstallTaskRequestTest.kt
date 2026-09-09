package com.itsaky.androidide.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallTaskRequestTest {
	@Test
	fun givenAQualifiedInstallTask_thenTheModuleAndTaskSuffixAreExtracted() {
		assertThat(installTaskRequestsIn(listOf(":app:installDebug")))
			.containsExactly(InstallTaskRequest(":app", "Debug"))
	}

	@Test
	fun givenANestedModule_thenTheFullModulePathIsKept() {
		assertThat(installTaskRequestsIn(listOf(":feature:app:installFreeRelease")))
			.containsExactly(InstallTaskRequest(":feature:app", "FreeRelease"))
	}

	@Test
	fun givenAnUnqualifiedInstallTask_thenTheModuleIsNull() {
		assertThat(installTaskRequestsIn(listOf("installDebug", ":installRelease")))
			.containsExactly(InstallTaskRequest(null, "Debug"), InstallTaskRequest(null, "Release"))
			.inOrder()
	}

	@Test
	fun givenUppercaseOrUnderscoredVariantNames_thenTheSuffixIsKeptVerbatim() {
		assertThat(installTaskRequestsIn(listOf(":app:installQA", ":app:installFree_betaDebug")))
			.containsExactly(InstallTaskRequest(":app", "QA"), InstallTaskRequest(":app", "Free_betaDebug"))
			.inOrder()
	}

	@Test
	fun givenARequest_thenItNamesTheAssembleTaskOfTheSameVariant() {
		assertThat(InstallTaskRequest(":app", "FreeRelease").assembleTaskName).isEqualTo("assembleFreeRelease")
	}

	@Test
	fun givenAndroidTestUninstallAndUnrelatedTasks_thenTheyAreIgnored() {
		assertThat(
			installTaskRequestsIn(
				listOf(":app:installDebugAndroidTest", ":app:uninstallDebug", ":app:assembleDebug", ":app:install"),
			),
		).isEmpty()
	}
}
