package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.type.DeclaredType
import openjdk.source.util.TreePath
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RegionAnalysisPrimitivesTest {
	@Test
	fun `an interface field initializer anchors as static`() {
		val f =
			fixture(
				"interface I {\n\tString S = compute();\n\tstatic String compute() { return \"x\"; }\n}",
			)
		val anchor = anchorMemberFor(f.pathAt("compute();"), f.root, f.trees, f.positions)
		assertThat(anchor).isNotNull()
		assertThat(anchor!!.isStatic).isTrue()
	}

	@Test
	fun `the type renderer declines an anonymous class type`() {
		val f = fixture("class F {\n\tvoid m() {\n\t\tvar h = new Object() { int f() { return 1; } };\n\t\th.f();\n\t}\n}")
		val anonType = f.trees.getElement(f.pathAt("h.f"))!!.asType()
		assertThat(TypeNames(f.root).render(anonType)).isNull()
	}

	@Test
	fun `a statements region without a block parent refuses instead of guessing`() {
		val f = fixture("class F { void m() { int a = 1; } }")
		val region = ExtractionRegion.Statements(statements = emptyList(), path = TreePath(f.root), span = TextSpan(0, 1))
		assertThrows(IllegalStateException::class.java) { regionPathsOf(region) }
	}

	@Test
	fun `a member type of a local class is reported by its local enclosing name`() {
		val f =
			fixture(
				"class F {\n\tvoid m() {\n\t\tclass Local { class Inner {} }\n\t\tLocal.Inner v = null;\n\t\tuse(v);\n\t}\n\tstatic void use(Object o) {}\n}",
			)
		val memberType = f.trees.getElement(f.pathAt("v)"))!!.asType()
		assertThat(localTypeNameIn(memberType)).isEqualTo("Local")
	}

	@Test
	fun `the local type name of an anonymous class is not empty`() {
		val f = fixture("class F {\n\tvoid m() {\n\t\tvar h = new Object() { int f() { return 1; } };\n\t\th.f();\n\t}\n}")
		val anonElement = (f.trees.getElement(f.pathAt("h.f"))!!.asType() as DeclaredType).asElement()
		assertThat(localTypeNameOf(anonElement)).isEqualTo("anonymous class")
	}

	@Test
	fun `a field of the local class the region sits in is not a captured local type`() {
		val f =
			fixture(
				"class F {\n\tvoid m() {\n\t\tclass Local {\n\t\t\tint fld;\n\t\t\tint g() { return fld + 1; }\n\t\t}\n\t}\n}",
			)
		val path = f.pathAt("fld + 1")
		val element = f.trees.getElement(path)!!
		val anchor = anchorMemberFor(path, f.root, f.trees, f.positions)!!
		val start = f.text.indexOf("fld + 1")
		val regionSpan = TextSpan(start, start + "fld + 1".length)
		assertThat(isCapturedLocalType(element, regionSpan, anchor, f.root, f.trees, f.positions)).isFalse()
	}

	@Test
	fun `a local class referenced outside it but inside the anchor member is a captured local type`() {
		val f =
			fixture(
				"class F {\n\tvoid m() {\n\t\tclass L {}\n\t\tuse(new L());\n\t}\n\tstatic void use(Object o) {}\n}",
			)
		val path = f.pathAt("L()")
		val element = f.trees.getElement(path)!!
		val anchor = anchorMemberFor(path, f.root, f.trees, f.positions)!!
		val start = f.text.indexOf("new L()")
		val regionSpan = TextSpan(start, start + "new L()".length)
		assertThat(isCapturedLocalType(element, regionSpan, anchor, f.root, f.trees, f.positions)).isTrue()
	}

	private val JavacFixture.positions get() = trees.sourcePositions

	private fun JavacFixture.pathAt(marker: String): TreePath {
		val pos = text.indexOf(marker)
		require(pos >= 0) { "the fixture contains no '$marker'" }
		return deepestPathAt(root, positions, pos, pos) ?: error("no tree at '$marker'")
	}

	private fun fixture(source: String): JavacFixture = JavacFixture(source).also { fixtures += it }

	@After
	fun closeFixtures() {
		fixtures.forEach(JavacFixture::close)
		fixtures.clear()
	}

	private val fixtures = mutableListOf<JavacFixture>()
}
