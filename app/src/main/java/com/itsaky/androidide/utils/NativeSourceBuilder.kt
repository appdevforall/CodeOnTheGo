package com.itsaky.androidide.utils

object NativeSourceBuilder {
	enum class Language(
		val sourceExtension: String,
	) {
		C("c"),
		CPP("cpp"),
	}

	enum class Kind {
		SOURCE,
		HEADER,
		CLASS,
		OTHER,
	}

	data class NativeFile(
		val name: String,
		val content: String,
	)

	private const val HEADER_EXTENSION = "h"

	private val FILE_NAME = Regex("[A-Za-z_][A-Za-z0-9_-]*")

	private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

	private val CPP_KEYWORDS =
		setOf(
			"alignas",
			"alignof",
			"and",
			"and_eq",
			"asm",
			"auto",
			"bitand",
			"bitor",
			"bool",
			"break",
			"case",
			"catch",
			"char",
			"char8_t",
			"char16_t",
			"char32_t",
			"class",
			"compl",
			"concept",
			"const",
			"consteval",
			"constexpr",
			"constinit",
			"const_cast",
			"continue",
			"co_await",
			"co_return",
			"co_yield",
			"decltype",
			"default",
			"delete",
			"do",
			"double",
			"dynamic_cast",
			"else",
			"enum",
			"explicit",
			"export",
			"extern",
			"false",
			"float",
			"for",
			"friend",
			"goto",
			"if",
			"inline",
			"int",
			"long",
			"mutable",
			"namespace",
			"new",
			"noexcept",
			"not",
			"not_eq",
			"nullptr",
			"operator",
			"or",
			"or_eq",
			"private",
			"protected",
			"public",
			"register",
			"reinterpret_cast",
			"requires",
			"return",
			"short",
			"signed",
			"sizeof",
			"static",
			"static_assert",
			"static_cast",
			"struct",
			"switch",
			"template",
			"this",
			"thread_local",
			"throw",
			"true",
			"try",
			"typedef",
			"typeid",
			"typename",
			"union",
			"unsigned",
			"using",
			"virtual",
			"void",
			"volatile",
			"wchar_t",
			"while",
			"xor",
			"xor_eq",
		)

	fun extensions(
		language: Language,
		kind: Kind,
	): List<String> =
		when (kind) {
			Kind.SOURCE -> listOf(language.sourceExtension)
			Kind.HEADER -> listOf(HEADER_EXTENSION)
			Kind.CLASS -> listOf(HEADER_EXTENSION, requireCpp(language).sourceExtension)
			Kind.OTHER -> emptyList()
		}

	fun maxNameLength(
		language: Language,
		kind: Kind,
		maxFileNameLength: Int,
	): Int = maxFileNameLength - (extensions(language, kind).maxOfOrNull { it.length + 1 } ?: 0)

	fun isValidName(
		name: String,
		language: Language,
		kind: Kind,
		maxFileNameLength: Int,
	): Boolean = followsNamingRule(name, kind) && name.length <= maxNameLength(language, kind, maxFileNameLength)

	fun createFiles(
		name: String,
		language: Language,
		kind: Kind,
	): List<NativeFile> {
		require(followsNamingRule(name, kind)) { "Invalid $kind name: '$name'" }
		val headerName = "$name.$HEADER_EXTENSION"
		return when (kind) {
			Kind.SOURCE -> {
				listOf(NativeFile("$name.${language.sourceExtension}", ""))
			}

			Kind.HEADER -> {
				listOf(NativeFile(headerName, header(body = null)))
			}

			Kind.CLASS -> {
				listOf(
					NativeFile(headerName, header(body = "class $name {\n};\n")),
					NativeFile("$name.${requireCpp(language).sourceExtension}", "#include \"$headerName\"\n"),
				)
			}

			Kind.OTHER -> {
				listOf(NativeFile(name, ""))
			}
		}
	}

	private fun followsNamingRule(
		name: String,
		kind: Kind,
	): Boolean =
		when (kind) {
			Kind.CLASS -> IDENTIFIER.matches(name) && name !in CPP_KEYWORDS
			Kind.SOURCE, Kind.HEADER -> FILE_NAME.matches(name)
			Kind.OTHER -> name.split('/').all { it.isNotBlank() && it != "." && it != ".." }
		}

	private fun requireCpp(language: Language): Language {
		require(language == Language.CPP) { "A class needs C++, not $language" }
		return language
	}

	private fun header(body: String?): String =
		buildString {
			appendLine("#pragma once")
			if (body != null) {
				appendLine()
				append(body)
			}
		}
}
