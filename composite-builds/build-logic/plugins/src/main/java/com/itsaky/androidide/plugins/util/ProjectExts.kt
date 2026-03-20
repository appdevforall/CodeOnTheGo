package com.itsaky.androidide.plugins.util

import org.gradle.api.Project
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import javax.inject.Inject

private interface ExecOps {
	@get:Inject
	val execOperations: ExecOperations
}

fun Project.execCompat(block: ExecSpec.() -> Unit): ExecResult =
	execOperations.exec(block)

fun Project.javaexecCompat(block: JavaExecSpec.() -> Unit): ExecResult =
	execOperations.javaexec(block)

val Project.execOperations: ExecOperations
	get() = objects.newInstance(ExecOps::class.java).execOperations
