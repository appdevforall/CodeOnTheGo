package org.appdevforall.cotg.quickbuild.domain.reload

import java.io.DataInputStream

/**
 * The hierarchy facts of one compiled class file - name, superclass, directly implemented
 * interfaces - which is what keeps [DeployPolicy]'s supertype index current across builds.
 *
 * Parsed by a constant-pool walk rather than a bytecode library; these fields sit right after
 * the constant pool, so nothing past the interface list is read. Names are in dot form with
 * `$` for nested classes (`com.example.Outer$Inner`).
 *
 * @property className the class's own FQN in dot form.
 * @property superClassName the direct superclass FQN; null only for `java.lang.Object` itself
 *   and for interfaces, which declare no superclass.
 * @property interfaceNames the directly implemented interface FQNs, in declaration order;
 *   inherited ones are not listed, since the header does not carry them.
 */
data class ClassHeader(
	val className: String,
	val superClassName: String?,
	val interfaceNames: List<String>,
) {
	companion object {
		// Reading the 0xCAFEBABE class-file magic back as a signed Int is negative, because
		// 0xCAFEBABE > Int.MAX_VALUE.
		private const val CLASS_MAGIC = -0x35014542 // 0xCAFEBABE

		/**
		 * Parses one class file's header.
		 *
		 * @param bytes the whole class file; only the prefix through the interface list is read,
		 *   so a truncated tail is harmless.
		 * @return the header, or null when the bytes are not a well-formed class file, which
		 *   callers skip rather than failing the build over.
		 */
		fun parse(bytes: ByteArray): ClassHeader? =
			try {
				DataInputStream(bytes.inputStream()).use(::parseStream)
			} catch (e: Exception) {
				// Swallowed because an over-restart is safe, whereas throwing would fail the
				// whole build over one unreadable class.
				null
			}

		private fun parseStream(input: DataInputStream): ClassHeader? {
			if (input.readInt() != CLASS_MAGIC) return null
			input.readUnsignedShort() // minor
			input.readUnsignedShort() // major

			val constantCount = input.readUnsignedShort()
			val utf8 = HashMap<Int, String>()
			val classNameIndex = HashMap<Int, Int>()
			// Walk the constant pool to collect just what resolves a class name: UTF-8 strings
			// (tag 1) and Class entries (tag 7, which point at a UTF-8 slot). Every other entry
			// type is skipped by its fixed byte width - we only need names, not the full pool.
			var index = 1
			while (index < constantCount) {
				val tag = input.readUnsignedByte()
				when (tag) {
					1 -> {
						utf8[index] = input.readUTF()
					}

					7 -> {
						classNameIndex[index] = input.readUnsignedShort()
					}

					8, 16, 19, 20 -> {
						input.skipBytes(2)
					}

					15 -> {
						input.skipBytes(3)
					}

					3, 4, 9, 10, 11, 12, 17, 18 -> {
						input.skipBytes(4)
					}

					5, 6 -> {
						input.skipBytes(8)
						index++ // longs/doubles occupy two constant-pool slots
					}

					else -> {
						return null
					}
				}
				index++
			}

			input.readUnsignedShort() // access flags
			val thisClass = className(input.readUnsignedShort(), classNameIndex, utf8) ?: return null
			val superClass = className(input.readUnsignedShort(), classNameIndex, utf8)
			val interfaces =
				(0 until input.readUnsignedShort()).mapNotNull {
					className(input.readUnsignedShort(), classNameIndex, utf8)
				}
			return ClassHeader(thisClass, superClass, interfaces)
		}

		private fun className(
			classIndex: Int,
			classNameIndex: Map<Int, Int>,
			utf8: Map<Int, String>,
		): String? = classNameIndex[classIndex]?.let(utf8::get)?.replace('/', '.')
	}
}
