package org.appdevforall.cotg.quickbuild.domain

import java.io.DataInputStream

/**
 * The hierarchy facts of one compiled class file - name, superclass, directly implemented
 * interfaces - which is what keeps [DeployPolicy]'s supertype index current across builds.
 *
 * Parsed by a constant-pool walk rather than a bytecode library; these fields sit right after
 * the constant pool, so nothing past the interface list is read. Names are in dot form with
 * `$` for nested classes (`com.example.Outer$Inner`).
 */
data class ClassHeader(
	val className: String,
	val superClassName: String?,
	val interfaceNames: List<String>,
) {
	companion object {
		private const val CLASS_MAGIC = -0x35014542 // 0xCAFEBABE

		/** Parses one class file's header. Null when [bytes] is not a well-formed class file. */
		fun parse(bytes: ByteArray): ClassHeader? =
			try {
				DataInputStream(bytes.inputStream()).use(::parseStream)
			} catch (e: Exception) {
				// Truncated or corrupt input; callers skip the file. An over-restart is safe,
				// whereas throwing would fail the whole build over one unreadable class.
				null
			}

		private fun parseStream(input: DataInputStream): ClassHeader? {
			if (input.readInt() != CLASS_MAGIC) return null
			input.readUnsignedShort() // minor
			input.readUnsignedShort() // major

			val constantCount = input.readUnsignedShort()
			val utf8 = HashMap<Int, String>()
			val classNameIndex = HashMap<Int, Int>()
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
