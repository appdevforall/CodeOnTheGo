package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUsageCollectorTest : KtLspTest() {

	private fun usageOf(ktFile: KtFile): ImportUsage =
		env.project.read { analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) } }

	@Test
	fun `type reference is recorded as used`() {
		val ktFile = createSourceFile(
			"UseType.kt",
			"""
			package p
			fun f(): java.io.File? = null
			fun g() { val x: java.io.File = java.io.File("a") }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("java.io.File" in usage.usedFqNames)
		assertTrue("java.io" in usage.usedPackages)
	}

	@Test
	fun `top-level function call is recorded as used`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			fun topLevelHelper() {}
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseFn.kt",
			"""
			package p
			import lib.topLevelHelper
			fun f() { topLevelHelper() }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.topLevelHelper" in usage.usedFqNames)
	}

	@Test
	fun `operator function used via symbol is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Money
			operator fun Money.plus(other: Money): Money = this
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseOp.kt",
			"""
			package p
			import lib.Money
			import lib.plus
			fun f(a: Money, b: Money) { val c = a + b }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.plus" in usage.usedFqNames)
	}

	@Test
	fun `unreferenced import is absent from usage`() {
		createSourceFile(
			"lib/Extra.kt",
			"""
			package lib
			class Extra
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"NoUse.kt",
			"""
			package p
			import lib.Extra
			fun f() {}
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertFalse("lib.Extra" in usage.usedFqNames)
	}

	@Test
	fun `array-access get operator used via subscript is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Foo
			operator fun Foo.get(i: Int): Int = i
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseGet.kt",
			"""
			package p
			import lib.Foo
			import lib.get
			fun f(foo: Foo) { val x = foo[0] }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.get" in usage.usedFqNames)
	}

	@Test
	fun `array-access set operator used via subscript assignment is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Foo
			operator fun Foo.set(i: Int, v: Int) {}
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseSet.kt",
			"""
			package p
			import lib.Foo
			import lib.set
			fun f(foo: Foo) { foo[0] = 1 }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.set" in usage.usedFqNames)
	}

	@Test
	fun `iterator operator used via for loop is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Foo
			operator fun Foo.iterator(): Iterator<Int> = listOf(1, 2, 3).iterator()
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseIterator.kt",
			"""
			package p
			import lib.Foo
			import lib.iterator
			fun f(foo: Foo) { for (x in foo) {} }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.iterator" in usage.usedFqNames)
	}

	@Test
	fun `componentN operators used via destructuring are recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Foo
			operator fun Foo.component1(): Int = 1
			operator fun Foo.component2(): Int = 2
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseDestructure.kt",
			"""
			package p
			import lib.Foo
			import lib.component1
			import lib.component2
			fun f(foo: Foo) { val (a, b) = foo }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.component1" in usage.usedFqNames)
		assertTrue("lib.component2" in usage.usedFqNames)
	}

	@Test
	fun `getValue operator used via property delegation is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			import kotlin.reflect.KProperty
			class Foo
			operator fun Foo.getValue(thisRef: Any?, property: KProperty<*>): Int = 1
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseDelegate.kt",
			"""
			package p
			import lib.Foo
			import lib.getValue
			fun f(foo: Foo) { val x: Int by foo }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.getValue" in usage.usedFqNames)
	}
}
