/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor.utils

import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.getNodeAt
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.TextUtils
import io.github.rosemoe.sora.util.IntPair

/**
 * Insert the given [text] at the end of this [Content] object.
 *
 * @param text The text to append.
 * @return The last line index after appending the given text.
 */
fun Content.append(text: CharSequence?): Int {
	if (lineCount <= 0) {
		return 0
	}

	val line = lineCount - 1
	var col = getColumnCount(line)
	if (col < 0) {
		col = 0
	}

	insert(line, col, text)
	return line
}

/**
 * Returns true if the given line is blank.
 */
fun Content.isBlankLine(line: Int): Boolean = getLine(line).trim { it.isWhitespace() || it == '\r' }.isEmpty()

/**
 * Returns true if the given line is not blank.
 */
fun Content.isNonBlankLine(line: Int): Boolean = !isBlankLine(line)

/**
 * Returns the index of the previous non-blank line.
 *
 * @return The index of the previous non-blank line or -1 if not found.
 */
fun Content.previousNonBlankLine(line: Int): Int {
	for (i in line - 1 downTo 0) {
		if (isNonBlankLine(i)) {
			return i
		}
	}

	return -1
}

/**
 * Returns the index of the next non-blank line.
 *
 * @return The index of the next non-blank line or -1 if not found.
 */
fun Content.nextNonBlankLine(line: Int): Int {
	for (i in line + 1 until length) {
		if (isNonBlankLine(i)) {
			return i
		}
	}

	return -1
}

/**
 * Returns the first [TSNode] at the given line number. The leading indentation is ignored.
 */
fun Content.getFirstNodeAtLine(
	tree: TSTree,
	line: Int,
	col: Int = Int.MIN_VALUE,
): TSNode? = getFirstNodeAtLine(tree.rootNode, line, col)

/**
 * Returns the last [TSNode] at the given line number.
 */
fun Content.getLastNodeAtLine(
	tree: TSTree,
	line: Int,
	col: Int = Int.MIN_VALUE,
): TSNode? = getLastNodeAtLine(tree.rootNode, line, col)

/**
 * Returns the first [TSNode] at the given line number. The leading indentation is ignored.
 */
fun Content.getFirstNodeAtLine(
	node: TSNode,
	line: Int,
	col: Int = Int.MIN_VALUE,
): TSNode? {
	if (line < 0 || line >= lineCount) {
		return null
	}

	var column = col
	if (column == Int.MIN_VALUE) {
		val contentLine = getLine(line)
		val (spaces, tabs) =
			TextUtils.countLeadingSpacesAndTabs(contentLine).let {
				IntPair.getFirst(it) to IntPair.getSecond(it)
			}

		column = (spaces + tabs) shl 1
	}

	// we need the byte offset in the line, so we need to multiply the char offset by 2 (shl 1)
	// also, we need not to expand the tabs to spaces as that would result in incorrect offset
	return node.getNodeAt(line, column)
}

/**
 * Returns the last [TSNode] at the given line number. This function also takes the leading
 * indentation into consideration.
 */
fun Content.getLastNodeAtLine(
	node: TSNode,
	line: Int,
	col: Int = Int.MIN_VALUE,
): TSNode? {
	if (line < 0 || line >= lineCount) {
		return null
	}

	val contentLine = getLine(line)

	var column = col
	if (column == Int.MIN_VALUE) {
		column = (contentLine.length - 1) shl 1
	}

	// we need the byte offset in the line, so we need to multiply the char offset by 2 (shl 1)
	return node.getNodeAt(line, column)
}

/**
 * Checks if the selected text in an XML file is an attribute.
 * An XML attribute is identified by having an '=' sign after the selection.
 */
fun IDEEditor.isXmlAttribute(): Boolean {
	val cursor = text.cursor
	val endLine = cursor.rightLine
	val endCol = cursor.rightColumn
	val lineText = text.getLine(endLine)
	val nextChar = lineText.drop(endCol).firstOrNull { !it.isWhitespace() }
	return nextChar == '='
}
