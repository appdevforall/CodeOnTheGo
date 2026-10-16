package com.itsaky.androidide.plugins.ai.prompt

/** Why a prompt config was refused, naming the file and the key, e.g. `rules.yml: rules[0].items`. */
class PromptConfigException(
	message: String,
	cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
