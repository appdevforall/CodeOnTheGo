package com.itsaky.androidide.lsp.kotlin.signaturehelp

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CallAtCursorFinder")

/**
 * Finds the innermost [KtCallElement] whose argument list directly contains [offset].
 *
 * Returns `null` when the cursor is inside a lambda body (trailing or argument lambda) or otherwise
 * not directly within a call's arguments, so that signature help is not shown there.
 */
internal fun findEnclosingCall(file: KtFile, offset: Int): KtCallElement? {
  // Prefer the element at the cursor; fall back to the element just before it (e.g. cursor at EOF
  // or immediately after '(').
  var node: PsiElement? = file.findElementAt(offset)
    ?: file.findElementAt((offset - 1).coerceAtLeast(0))

  if (node == null) {
    logger.debug("No PSI element found at offset {} in {}", offset, file.name)
    return null
  }

  while (node != null && node !is KtFile) {
    // Crossing a lambda body before reaching a call's arguments means we are inside the lambda.
    if (node is KtFunctionLiteral) {
      logger.debug("Offset {} in {} is inside a lambda body; not showing signature help", offset, file.name)
      return null
    }
    if (node is KtCallElement) {
      val callee = node.calleeExpression
      if (callee != null && offset > callee.textRange.endOffset) {
        logger.debug("Found enclosing call '{}' for offset {} in {}", callee.text, offset, file.name)
        return node
      }
    }
    node = node.parent
  }
  logger.debug("No enclosing call found for offset {} in {}", offset, file.name)
  return null
}
