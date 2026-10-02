package com.itsaky.androidide.lsp.models

class CodeFormatException(
	message: String,
	cause: Throwable,
) : Exception(message, cause)
