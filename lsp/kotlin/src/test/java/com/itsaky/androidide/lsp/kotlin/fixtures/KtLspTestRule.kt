package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.lsp.kotlin.compiler.write
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class KtLspTestRule : TestRule {

	val tempDir = TemporaryFolder()
	lateinit var env: KtLspTestEnvironment
		private set

	override fun apply(
		statement: Statement?,
		p1: Description?
	): Statement {
		return object : Statement() {
			override fun evaluate() {
				try {
					tempDir.create()

					val sourceRoot = tempDir.newFolder("src").toPath()
					env = KtLspTestEnvironment(listOf(sourceRoot))

					statement?.evaluate()
				} finally {
					if (::env.isInitialized) {
						env.project.write {
							// TODO: This fails in test cases, ignored for now
							// env.close()
						}
					}
				}
			}
		}
	}
}