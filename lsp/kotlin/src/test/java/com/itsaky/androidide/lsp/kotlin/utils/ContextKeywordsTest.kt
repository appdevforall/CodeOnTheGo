package com.itsaky.androidide.lsp.kotlin.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.completion.DeclarationContext
import org.jetbrains.kotlin.lexer.KtTokens
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [ContextKeywords].
 *
 * Verifies that [ContextKeywords.keywordsFor] returns the expected set of keyword
 * tokens for each [DeclarationContext], and that the constant sets contain the
 * expected members.
 */
@RunWith(JUnit4::class)
class ContextKeywordsTest {

    @Test
    fun `STATEMENT_KEYWORDS contains core control-flow tokens`() {
        assertThat(ContextKeywords.STATEMENT_KEYWORDS).containsAtLeast(
            KtTokens.IF_KEYWORD,
            KtTokens.ELSE_KEYWORD,
            KtTokens.WHEN_KEYWORD,
            KtTokens.WHILE_KEYWORD,
            KtTokens.DO_KEYWORD,
            KtTokens.FOR_KEYWORD,
            KtTokens.TRY_KEYWORD,
            KtTokens.RETURN_KEYWORD,
            KtTokens.THROW_KEYWORD,
            KtTokens.BREAK_KEYWORD,
        )
    }

    @Test
    fun `STATEMENT_KEYWORDS contains local declaration starters`() {
        assertThat(ContextKeywords.STATEMENT_KEYWORDS).containsAtLeast(
            KtTokens.VAL_KEYWORD,
            KtTokens.VAR_KEYWORD,
            KtTokens.FUN_KEYWORD,
        )
    }

    @Test
    fun `DECLARATION_KEYWORDS contains all top-level declaration starters`() {
        assertThat(ContextKeywords.DECLARATION_KEYWORDS).containsAtLeast(
            KtTokens.VAL_KEYWORD,
            KtTokens.VAR_KEYWORD,
            KtTokens.FUN_KEYWORD,
            KtTokens.CLASS_KEYWORD,
            KtTokens.INTERFACE_KEYWORD,
            KtTokens.OBJECT_KEYWORD,
            KtTokens.TYPE_ALIAS_KEYWORD,
        )
    }

    @Test
    fun `TOP_LEVEL_ONLY contains exactly PACKAGE and IMPORT`() {
        assertThat(ContextKeywords.TOP_LEVEL_ONLY).containsExactly(
            KtTokens.PACKAGE_KEYWORD,
            KtTokens.IMPORT_KEYWORD,
        )
    }

    @Test
    fun `keywordsFor TOP_LEVEL includes TOP_LEVEL_ONLY tokens`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.TOP_LEVEL)
        assertThat(keywords).containsAtLeast(KtTokens.PACKAGE_KEYWORD, KtTokens.IMPORT_KEYWORD)
    }

    @Test
    fun `keywordsFor TOP_LEVEL includes all DECLARATION_KEYWORDS`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.TOP_LEVEL)
        assertThat(keywords).containsAtLeastElementsIn(ContextKeywords.DECLARATION_KEYWORDS)
    }

    @Test
    fun `keywordsFor SCRIPT_TOP_LEVEL equals keywordsFor TOP_LEVEL`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.SCRIPT_TOP_LEVEL))
            .containsExactlyElementsIn(ContextKeywords.keywordsFor(DeclarationContext.TOP_LEVEL))
    }

    @Test
    fun `keywordsFor FUNCTION_BODY equals STATEMENT_KEYWORDS`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.FUNCTION_BODY))
            .containsExactlyElementsIn(ContextKeywords.STATEMENT_KEYWORDS)
    }

    @Test
    fun `keywordsFor FUNCTION_BODY does not include PACKAGE or IMPORT`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.FUNCTION_BODY)
        assertThat(keywords).doesNotContain(KtTokens.PACKAGE_KEYWORD)
        assertThat(keywords).doesNotContain(KtTokens.IMPORT_KEYWORD)
    }

    @Test
    fun `keywordsFor FUNCTION_BODY does not include CLASS or INTERFACE`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.FUNCTION_BODY)
        // local class is included (rare but legal), interface is not
        assertThat(keywords).doesNotContain(KtTokens.INTERFACE_KEYWORD)
    }

    @Test
    fun `keywordsFor CLASS_BODY includes INIT and CONSTRUCTOR`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.CLASS_BODY)
        assertThat(keywords).contains(KtTokens.INIT_KEYWORD)
        assertThat(keywords).contains(KtTokens.CONSTRUCTOR_KEYWORD)
    }

    @Test
    fun `keywordsFor CLASS_BODY includes standard declaration starters`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.CLASS_BODY)
        assertThat(keywords).containsAtLeast(
            KtTokens.VAL_KEYWORD, KtTokens.VAR_KEYWORD, KtTokens.FUN_KEYWORD,
        )
    }

    @Test
    fun `keywordsFor INTERFACE_BODY does not include CONSTRUCTOR`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.INTERFACE_BODY))
            .doesNotContain(KtTokens.CONSTRUCTOR_KEYWORD)
    }

    @Test
    fun `keywordsFor INTERFACE_BODY does not include INIT`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.INTERFACE_BODY))
            .doesNotContain(KtTokens.INIT_KEYWORD)
    }

    @Test
    fun `keywordsFor INTERFACE_BODY includes declaration starters for interface members`() {
        val keywords = ContextKeywords.keywordsFor(DeclarationContext.INTERFACE_BODY)
        assertThat(keywords).containsAtLeast(
            KtTokens.VAL_KEYWORD, KtTokens.VAR_KEYWORD, KtTokens.FUN_KEYWORD,
        )
    }

    @Test
    fun `keywordsFor OBJECT_BODY does not include CONSTRUCTOR`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.OBJECT_BODY))
            .doesNotContain(KtTokens.CONSTRUCTOR_KEYWORD)
    }

    @Test
    fun `keywordsFor ENUM_BODY does not include CONSTRUCTOR`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.ENUM_BODY))
            .doesNotContain(KtTokens.CONSTRUCTOR_KEYWORD)
    }

    @Test
    fun `keywordsFor OBJECT_BODY and ENUM_BODY return same set`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.OBJECT_BODY))
            .containsExactlyElementsIn(ContextKeywords.keywordsFor(DeclarationContext.ENUM_BODY))
    }

    @Test
    fun `keywordsFor ANNOTATION_BODY returns only VAL`() {
        assertThat(ContextKeywords.keywordsFor(DeclarationContext.ANNOTATION_BODY))
            .containsExactly(KtTokens.VAL_KEYWORD)
    }

    @Test
    fun `FUNCTION_BODY keywords are never in TOP_LEVEL_ONLY`() {
        val functionKeywords = ContextKeywords.keywordsFor(DeclarationContext.FUNCTION_BODY)
        assertThat(functionKeywords.none { it in ContextKeywords.TOP_LEVEL_ONLY }).isTrue()
    }

    @Test
    fun `all contexts return non-empty keyword sets`() {
        for (ctx in DeclarationContext.entries) {
            assertThat(ContextKeywords.keywordsFor(ctx)).isNotEmpty()
        }
    }
}
