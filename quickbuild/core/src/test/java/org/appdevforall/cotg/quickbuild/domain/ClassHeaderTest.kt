package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * Parses REAL class files - this test's own compiled fixtures, loaded from the test
 * classpath - so the constant-pool walk is verified against genuine kotlinc output,
 * not hand-crafted bytes.
 */
class ClassHeaderTest {
	private open class Base

	private class Sample :
		Base(),
		Serializable

	private fun bytesOf(clazz: Class<*>): ByteArray {
		val resource = clazz.name.replace('.', '/') + ".class"
		return clazz.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
	}

	@Test
	fun `parses name, superclass and interfaces of a real nested class`() {
		val header = ClassHeader.parse(bytesOf(Sample::class.java))

		assertThat(header).isNotNull()
		assertThat(header!!.className)
			.isEqualTo("org.appdevforall.cotg.quickbuild.domain.ClassHeaderTest\$Sample")
		assertThat(header.superClassName)
			.isEqualTo("org.appdevforall.cotg.quickbuild.domain.ClassHeaderTest\$Base")
		assertThat(header.interfaceNames).containsExactly("java.io.Serializable")
	}

	@Test
	fun `a plain class reports Object as superclass and no interfaces`() {
		val header = ClassHeader.parse(bytesOf(Base::class.java))

		assertThat(header).isNotNull()
		assertThat(header!!.superClassName).isEqualTo("java.lang.Object")
		assertThat(header.interfaceNames).isEmpty()
	}

	@Test
	fun `constant-pool entries with two slots do not derail the walk`() {
		// String/numeric constants (incl. Long and Double, which occupy two slots)
		// populate the pool ahead of the header fields.
		val header = ClassHeader.parse(bytesOf(ConstantsFixture::class.java))

		assertThat(header).isNotNull()
		assertThat(header!!.className)
			.isEqualTo("org.appdevforall.cotg.quickbuild.domain.ConstantsFixture")
	}

	@Test
	fun `garbage bytes parse to null, never a throw`() {
		assertThat(ClassHeader.parse(ByteArray(0))).isNull()
		assertThat(ClassHeader.parse(byteArrayOf(1, 2, 3, 4, 5))).isNull()
		assertThat(ClassHeader.parse("not a class file at all".toByteArray())).isNull()
	}

	@Test
	fun `a truncated class file parses to null`() {
		val bytes = bytesOf(Sample::class.java)

		assertThat(ClassHeader.parse(bytes.copyOf(12))).isNull()
	}
}

/** Fixture whose constant pool carries long/double constants (two-slot entries). */
@Suppress("unused")
private class ConstantsFixture {
	val longConstant: Long = 0x1234_5678_9ABCL
	val doubleConstant: Double = 3.14159265358979
	val stringConstant: String = "quick-build"
}
