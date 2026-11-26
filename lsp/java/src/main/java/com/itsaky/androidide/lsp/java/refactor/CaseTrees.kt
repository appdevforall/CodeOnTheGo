package com.itsaky.androidide.lsp.java.refactor

import openjdk.source.tree.CaseTree
import openjdk.source.tree.Tree

// The vendored javac still marks CaseTree's switch-rule API (getCaseKind, CaseKind, getBody) deprecated,
// as JDK 12 did while switch rules were a preview. JDK 14 made that API standard and it has no
// replacement, so these accessors keep the suppressions in one place.

/** Whether this case is a `case L ->` rule. */
@Suppress("DEPRECATION")
internal val CaseTree.isRule: Boolean
	get() = caseKind == CaseTree.CaseKind.RULE

/** Whether this case is a `case L:` statement group. */
@Suppress("DEPRECATION")
internal val CaseTree.isStatementGroup: Boolean
	get() = caseKind == CaseTree.CaseKind.STATEMENT

/** The expression or statement after a rule's arrow, or null for a statement group. */
@Suppress("DEPRECATION")
internal val CaseTree.ruleBody: Tree?
	get() = body
