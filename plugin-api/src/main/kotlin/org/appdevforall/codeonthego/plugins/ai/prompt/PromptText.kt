package org.appdevforall.codeonthego.plugins.ai.prompt

/**
 * Prompt wording from config: itself a template, so [PromptTemplateEngine] renders it where it is
 * placed, with the values in scope there. A plain String value is inserted verbatim instead.
 *
 * @property template the text, with its `{{NAME}}` tags.
 * @property label where the text came from, e.g. `rules.yml: rules[0].items[1]`, for error messages.
 */
data class PromptText(
	val template: String,
	val label: String,
)
