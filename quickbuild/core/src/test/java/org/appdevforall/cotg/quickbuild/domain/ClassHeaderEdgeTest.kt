package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Hand-crafted class bytes for the constant-pool shapes real kotlinc fixtures cannot
 * produce on demand: MethodHandle/MethodType entries, an unknown tag, a broken
 * this_class, and a zero super_class. Complements [ClassHeaderTest]'s real-fixture
 * coverage.
 */
class ClassHeaderEdgeTest {
	private class ClassBytes {
		private val pool = mutableListOf<(DataOutputStream) -> Unit>()

		/** 1-based index of the entry just added. */
		private fun add(writer: (DataOutputStream) -> Unit): Int {
			pool += writer
			return pool.size
		}

		fun utf8(value: String) =
			add {
				it.writeByte(1)
				it.writeUTF(value)
			}

		fun classRef(nameIndex: Int) =
			add {
				it.writeByte(7)
				it.writeShort(nameIndex)
			}

		fun stringRef(utf8Index: Int) =
			add {
				it.writeByte(8)
				it.writeShort(utf8Index)
			}

		fun methodHandle() =
			add {
				it.writeByte(15)
				it.writeByte(1)
				it.writeShort(0)
			}

		fun methodType(descriptorIndex: Int) =
			add {
				it.writeByte(16)
				it.writeShort(descriptorIndex)
			}

		fun unknownTag() = add { it.writeByte(99) }

		fun build(
			thisClass: Int,
			superClass: Int,
			interfaces: List<Int> = emptyList(),
		): ByteArray {
			val bytes = ByteArrayOutputStream()
			DataOutputStream(bytes).use { out ->
				out.writeInt(-0x35014542) // 0xCAFEBABE
				out.writeShort(0) // minor
				out.writeShort(52) // major
				out.writeShort(pool.size + 1)
				pool.forEach { it(out) }
				out.writeShort(0x0021) // access flags
				out.writeShort(thisClass)
				out.writeShort(superClass)
				out.writeShort(interfaces.size)
				interfaces.forEach(out::writeShort)
			}
			return bytes.toByteArray()
		}
	}

	@Test
	fun `method handle and method type entries are skipped without derailing the walk`() {
		val b = ClassBytes()
		val name = b.utf8("com/example/Made")
		val thisClass = b.classRef(name)
		b.stringRef(name)
		b.methodHandle()
		b.methodType(name)
		val objectName = b.utf8("java/lang/Object")
		val objectClass = b.classRef(objectName)

		val header = ClassHeader.parse(b.build(thisClass, objectClass))

		assertThat(header).isNotNull()
		assertThat(header!!.className).isEqualTo("com.example.Made")
		assertThat(header.superClassName).isEqualTo("java.lang.Object")
	}

	@Test
	fun `an unknown constant-pool tag parses to null, never a throw`() {
		val b = ClassBytes()
		val name = b.utf8("com/example/Made")
		val thisClass = b.classRef(name)
		b.unknownTag()

		assertThat(ClassHeader.parse(b.build(thisClass, 0))).isNull()
	}

	@Test
	fun `a this_class that is not a Class entry parses to null`() {
		val b = ClassBytes()
		val name = b.utf8("com/example/Made")
		b.classRef(name)

		// this_class points at the Utf8 entry, not the Class entry.
		assertThat(ClassHeader.parse(b.build(thisClass = name, superClass = 0))).isNull()
	}

	@Test
	fun `a zero super_class reports no superclass`() {
		// java/lang/Object itself carries super_class = 0.
		val b = ClassBytes()
		val name = b.utf8("java/lang/Object")
		val thisClass = b.classRef(name)

		val header = ClassHeader.parse(b.build(thisClass, superClass = 0))

		assertThat(header).isNotNull()
		assertThat(header!!.superClassName).isNull()
	}

	@Test
	fun `an interface entry with a dangling index is skipped, not fatal`() {
		val b = ClassBytes()
		val name = b.utf8("com/example/Made")
		val thisClass = b.classRef(name)
		val ifaceName = b.utf8("java/io/Serializable")
		val iface = b.classRef(ifaceName)

		val header = ClassHeader.parse(b.build(thisClass, superClass = 0, interfaces = listOf(iface, 0)))

		assertThat(header).isNotNull()
		assertThat(header!!.interfaceNames).containsExactly("java.io.Serializable")
	}
}
