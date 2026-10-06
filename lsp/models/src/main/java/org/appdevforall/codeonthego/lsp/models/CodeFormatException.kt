package org.appdevforall.codeonthego.lsp.models

class CodeFormatException(
	message: String,
	cause: Throwable,
) : Exception(message, cause)
