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

package com.itsaky.androidide.treesitter.api

import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSLookaheadIterator
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSPoint
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryCapture
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSQueryMatch
import com.itsaky.androidide.treesitter.TSQueryPredicateStep
import com.itsaky.androidide.treesitter.TSRange
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.TSTreeCursor
import com.itsaky.androidide.treesitter.TSTreeCursorNode
import com.itsaky.androidide.treesitter.string.SynchronizedUTF16String
import com.itsaky.androidide.treesitter.string.UTF16String
import com.itsaky.androidide.treesitter.util.TSObjectFactory

/**
 * [TSObjectFactory] implementation for AndroidIDE.
 *
 * @author Akash Yadav
 */
class TreeSitterObjectFactory : TSObjectFactory {
	override fun createInputEdit(
		startByte: Int,
		oldEndByte: Int,
		newEndByte: Int,
		startPoint: TSPoint,
		oldEndPoint: TSPoint,
		newEndPoint: TSPoint,
	): TSInputEdit =
		TreeSitterInputEdit.obtain(
			startByte,
			oldEndByte,
			newEndByte,
			startPoint,
			oldEndPoint,
			newEndPoint,
		)

	override fun createParser(parserPointer: Long): TSParser = TreeSitterParser.obtain(parserPointer)

	override fun createQuery(queryPointer: Long): TSQuery = TreeSitterQuery.obtain(queryPointer)

	override fun createQueryCursor(pointer: Long): TSQueryCursor = TreeSitterQueryCursor.obtain(pointer)

	override fun createPoint(
		row: Int,
		column: Int,
	): TSPoint = TreeSitterPoint.obtain(row, column)

	override fun createRange(
		startByte: Int,
		endByte: Int,
		startPoint: TSPoint,
		endPoint: TSPoint?,
	): TSRange = TreeSitterRange.obtain(startByte, endByte, startPoint, endPoint)

	override fun createRangeArr(size: Int): Array<TSRange?> = arrayOfNulls(size)

	override fun createTree(pointer: Long): TSTree = TreeSitterTree.obtain(pointer)

	override fun createTreeCursor(pointer: Long): TSTreeCursor = TreeSitterTreeCursor.obtain(pointer)

	override fun createNode(
		context0: Int,
		context1: Int,
		context2: Int,
		context3: Int,
		id: Long,
		treePointer: Long,
	): TSNode = TreeSitterNode.obtain(context0, context1, context2, context3, id, treePointer)

	override fun createQueryCapture(
		node: TSNode?,
		index: Int,
	): TSQueryCapture = TreeSitterQueryCapture.obtain(node, index)

	override fun createQueryMatch(
		id: Int,
		patternIndex: Int,
		captures: Array<TSQueryCapture?>?,
	): TSQueryMatch = TreeSitterQueryMatch.obtain(id, patternIndex, captures)

	override fun createQueryPredicateStep(
		type: Int,
		valueId: Int,
	): TSQueryPredicateStep = TreeSitterQueryPredicateStep.obtain(type, valueId)

	override fun createQueryPredicateStepArr(size: Int): Array<TSQueryPredicateStep?> = arrayOfNulls(size)

	override fun createTreeCursorNode(
		type: String?,
		name: String?,
		startByte: Int,
		endByte: Int,
	): TSTreeCursorNode = TreeSitterTreeCursorNode.obtain(type, name, startByte, endByte)

	override fun createLookaheadIterator(pointer: Long): TSLookaheadIterator = TreeSitterLookaheadIterator.obtain(pointer)

	override fun createLanguage(
		name: String?,
		pointers: LongArray?,
	): TSLanguage = TreeSitterNativeLanguage.obtain(name, pointers)

	override fun createString(
		pointer: Long,
		isSynchronized: Boolean,
	): UTF16String = if (isSynchronized) SynchronizedUTF16String(pointer) else UTF16String(pointer)
}
