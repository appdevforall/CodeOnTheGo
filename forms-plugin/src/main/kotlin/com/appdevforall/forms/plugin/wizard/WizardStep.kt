package com.appdevforall.forms.plugin.wizard

/**
 * The four wizard steps. Order is fixed; the host Activity uses the [stringResId]
 * for the step title in the toolbar slot.
 */
internal enum class WizardStep(val stringResId: Int) {
    CAPTURE(com.appdevforall.forms.plugin.R.string.forms_wizard_step1_title),
    REVIEW_FIELDS(com.appdevforall.forms.plugin.R.string.forms_wizard_step2_title),
    RULES(com.appdevforall.forms.plugin.R.string.forms_wizard_step3_title),
    SUBMIT(com.appdevforall.forms.plugin.R.string.forms_wizard_step4_title);

    fun next(): WizardStep? = values().getOrNull(ordinal + 1)
    fun previous(): WizardStep? = values().getOrNull(ordinal - 1)

    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == values().size - 1
}
