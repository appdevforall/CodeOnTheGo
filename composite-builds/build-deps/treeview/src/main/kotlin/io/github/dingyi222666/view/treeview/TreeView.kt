/*
 * TreeView - An TreeView implement in Android with RecyclerView written in kotlin.
 *  https://github.com/dingyi222666/TreeView
 *
 * Copyright (C) 2023-2024. dingyi222666@foxmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.dingyi222666.view.treeview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Checkable
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

/**
 * TreeView.
 *
 * TreeView based on RecyclerView implementation.
 *
 * The data in the [AbstractTree] can be displayed.
 *
 * @param T Data type of [tree]
 * @see [AbstractTree]
 * @see [Tree]
 * @see [TreeViewBinder]
 */
class TreeView<T : Any> @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr), TreeNodeEventListener<T> {

    private var horizontalOffset = 0f
    private var maxChildWidth = 0
    private var pointerId = 0
    private var pointerLastX = 0f
    private var slopExceeded = false
    private var needsWidthRefresh = true

    private val maxHorizontalOffset: Float
        get() = (maxChildWidth - width * 0.75f).coerceAtLeast(0f)

    /**
     * Tree structure.
     *
     * Set it to allow TreeView to fetch node data.
     */
    lateinit var tree: Tree<T>

    /**
     * TreeView Binder.
     *
     * Set it to bind between node and view
     */
    lateinit var binder: TreeViewBinder<T>

    /**
     * Event listener for the node.
     *
     * Set it to listen for event on the node, such as a click on the node event.
     */
    var nodeEventListener: TreeNodeEventListener<T> =
        EmptyTreeNodeEventListener() as TreeNodeEventListener<T>

    /**
     * TreeView Selection Mode.
     *
     * Set it to allow TreeView to select nodes.
     *
     */
    var selectionMode by Delegates.observable(SelectionMode.NONE) { _, old, new ->
        if (old == new) {
            return@observable
        }
        selectionRefresh(old, new)
    }

    /**
     * Whether to support drag and drop.
     *
     * Set it to allow TreeView to drag and drop nodes.
     *
     * **Note: This is still an experimental feature and some problems may arise.**
     */
    var supportDragging: Boolean = false

    /**
     * Whether horizontal scrolling is supported.
     *
     * In most cases, you don't need to turn it on.
     *
     * you only need to turn it on when the indentation of the node binded view is too large, or when the width of the view itself is too large for the screen to be fully displayed.
     *
     * **Note: This is still an experimental feature and some problems may arise.**
     */
    var supportHorizontalScroll by Delegates.observable(false) { _, old, new ->
        if (old == new) {
            return@observable
        }
        if (!new) {
            horizontalOffset = 0f
        }
        defaultRefresh()
    }

    /**
     * Whether to select the node when long clicking.
     *
     * Set it to allow TreeView to select nodes when long clicking.
     *
     *
     * **Note: You need to set [selectionMode] to a value other than [SelectionMode.NONE] to use this feature.**
     *
     * @see [selectionMode]
     */
    var selectNodeWhenLongClick = true

    private lateinit var _adapter: Adapter
    private val _itemTouchHelperCallback: ItemTouchHelperCallback

    private var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main)

    private var isHorizontalScrolling = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val scrollListener = object : OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (supportHorizontalScroll) {
                recalculateMaxWidth()
            }
        }
    }

    init {
        this._itemTouchHelperCallback = ItemTouchHelperCallback()

        val itemTouchHelper = ItemTouchHelper(_itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(this)

        addOnScrollListener(scrollListener)
    }

    /**
     * Bind the concurrent scope of the TreeView.
     *
     * Some operations need to run on a concurrent scope, set it to make TreeView work better.
     *
     * Note: TreeView is not responsible for closing the concurrent scope, it is up to the caller to do so.
     */
    fun bindCoroutineScope(coroutineScope: CoroutineScope) {
        this.coroutineScope = coroutineScope
    }


    /**
     * Refresh the data.
     *
     * Call this method to refresh the node data to display on the TreeView.
     *
     * @param [fastRefresh] Whether to quick refresh or not.
     * @param [node] The node to be refreshed; if the value is not null, only the child nodes under the node will be refreshed.
     * @param [withExpandable] Whether to refresh only the expanded nodes. If true, only the expanded nodes will be refreshed, otherwise a full refresh will be performed. The default value is false
     *
     * If true, only data from the cache will be fetched instead of calling the [TreeNodeGenerator]
     *
     * @see [AbstractTree.toSortedList]
     */
    suspend fun refresh(
        fastRefresh: Boolean = false, node: TreeNode<T>? = null, withExpandable: Boolean = false
    ) {
        if (!this::_adapter.isInitialized) {
            initAdapter()
        }

        nodeEventListener.onRefresh(true)

        var fastRefreshOnLocal = fastRefresh

        if (node != null) {
            tree.refreshWithChild(node, withExpandable)
            fastRefreshOnLocal = true
        }

        val list = tree.toSortedList(fastVisit = fastRefreshOnLocal)

        _adapter.submitList(list)

        nodeEventListener.onRefresh(false)
    }

    private fun getViewHolder(index: Int): ViewHolder? {
        if (adapter == null || adapter?.itemCount!! < 1) {
            return null
        }
        val count = _adapter.itemCount
        if (index !in 0 until count) {
            return null
        }
        return findViewHolderForAdapterPosition(index) as ViewHolder?
    }


    /**
     * Switch the state of the node.
     *
     * If the node is a leaf node, then the method will do nothing.
     * Otherwise, it will switch the expand and collapse state of the node.
     *
     * @param [node] Node that need to switch node state
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun toggleNode(node: TreeNode<T>, fullRefresh: Boolean = true) {
        if (!node.isChild) {
            return
        }
        if (node.expand) {
            collapseNode(node, fullRefresh)
        } else {
            expandNode(node, fullRefresh)
        }
    }

    /**
     * Expand all nodes.
     *
     * Start from the root node and set all non leaf nodes to expanded state
     *
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun expandAll(fullRefresh: Boolean = true) {
        tree.expandAll(fullRefresh)
        refresh(fullRefresh)
    }

    /**
     * Expand the given node and its children start from it
     *
     * Expand the node from the given node, which also includes all its children.
     *
     * @param [node] Node to be expanded
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun expandAll(node: TreeNode<T>, fullRefresh: Boolean = true) {
        tree.expandAll(node, fullRefresh)
        refresh(fullRefresh, node)
    }

    /**
     * expand node.
     *
     * This will expand the children of the given node with no change in the state of the children.
     * This is especially different from [expandAll].
     *
     * @param [node] Node to be expanded
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun expandNode(node: TreeNode<T>, fullRefresh: Boolean = true) {
        tree.expandNode(node, fullRefresh)
        refresh(fullRefresh, node)
    }

    /**
     * Collapse all nodes.
     *
     * Start from the root node and set all non leaf nodes to collapsed state
     *
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun collapseAll(fullRefresh: Boolean = true) {
        tree.collapseAll(fullRefresh)
        refresh(fullRefresh)
    }

    /**
     * Collapse the given node and its children start from it
     *
     * Collapse the node from the given node, which also includes all its children.
     *
     * @param [node] Node to be collapsed
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun collapseAll(node: TreeNode<T>, fullRefresh: Boolean = true) {
        tree.collapseAll(node, fullRefresh)
        refresh(fullRefresh, node)
    }

    /**
     * collapse node.
     *
     * This will collapse the children of the given node with no change in the state of the children.
     * This is especially different from [collapseAll].
     *
     * @param [node] Node to be collapsed
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun collapseNode(node: TreeNode<T>, fullRefresh: Boolean = true) {
        tree.collapseNode(node, fullRefresh)
        refresh(fullRefresh, node)
    }

    /**
     * Expand nodes of the given depth
     *
     * This will expand the nodes that have the given depth and does not include its children
     *
     * @param [depth] Given depth
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
     */
    suspend fun collapseFrom(depth: Int, fullRefresh: Boolean = true) {
        tree.collapseFrom(depth, fullRefresh)
        refresh(fullRefresh)
    }


    /**
     * Collapse the nodes of the given depth
     *
     * This will collapse the nodes that have the given depth and does not include its children
     *
     * @param [depth] Given depth
     * @param [fullRefresh] Whether to fetch nodes from the node generator when refreshed, if false, then nodes will be fetched from the cache
    .
     */
    suspend fun expandUntil(depth: Int, fullRefresh: Boolean = true) {
        tree.expandUntil(depth, fullRefresh)
        refresh(true)
    }

    /**
     * Select the given node
     *
     * @param [node] Node to be selected
     * @param [selected] Whether to select the node
     */
    suspend fun selectNode(node: TreeNode<T>, selected: Boolean) {
        val oldSelectedNode = tree.getSelectedNodes().getOrNull(0)
        if (selectionMode == SelectionMode.SINGLE && oldSelectedNode != null && oldSelectedNode.path != node.path) {
            tree
                .selectNode(oldSelectedNode, false, selectChild = false)
        }
        tree.selectNode(node, selected, selectionMode == SelectionMode.MULTIPLE_WITH_CHILDREN)
        refresh(true, node)
    }

    private fun initAdapter() {
        _adapter = Adapter(binder)
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = _adapter
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Update horizontal offset
        horizontalOffset = if (supportHorizontalScroll) {
            horizontalOffset.coerceIn(0f, maxHorizontalOffset)
        } else 0f
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (supportHorizontalScroll && (changed || needsWidthRefresh)) {
            recalculateMaxWidth()
            needsWidthRefresh = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Called by super's dispatchTouchEvent
        if (supportHorizontalScroll && horizontalOffset != 0f) {
            // Use original event for self
            return super.onTouchEvent(generateTranslatedMotionEvent(e, -horizontalOffset, 0f))
        }
        return super.onTouchEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!supportHorizontalScroll) {
            return super.dispatchTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = ev.getPointerId(0)
                initialTouchX = ev.x
                initialTouchY = ev.y
                pointerLastX = ev.x
                isHorizontalScrolling = false
                slopExceeded = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (ev.getPointerId(ev.actionIndex) == pointerId) {
                    val dx = abs(ev.x - initialTouchX)
                    val dy = abs(ev.y - initialTouchY)

                    // Determine if this is a horizontal scroll
                    if (!isHorizontalScrolling && dx > touchSlop && dx > dy * 2) {
                        isHorizontalScrolling = true
                        slopExceeded = true
                    }

                    if (isHorizontalScrolling) {
                        horizontalOffset = (pointerLastX - ev.x + horizontalOffset)
                            .coerceAtLeast(0f)
                            .coerceAtMost(maxHorizontalOffset)
                        pointerLastX = ev.x
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerId = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isHorizontalScrolling) {
                    isHorizontalScrolling = false
                    return true
                }
            }
        }

        return if (isHorizontalScrolling) {
            true
        } else if (horizontalOffset != 0f) {
            super.dispatchTouchEvent(generateTranslatedMotionEvent(ev, horizontalOffset, 0f))
        } else {
            super.dispatchTouchEvent(ev)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        // Translate canvas for rendering children
        canvas.translate(-horizontalOffset, 0f)
        super.dispatchDraw(canvas)
        canvas.restore()
    }


    private fun checkCanSelect(): Boolean {
        return when (selectionMode) {
            SelectionMode.NONE -> false
            SelectionMode.SINGLE, SelectionMode.MULTIPLE, SelectionMode.MULTIPLE_WITH_CHILDREN -> true
        }
    }

    private suspend fun trySelect(node: TreeNode<T>): Boolean {
        if (!checkCanSelect()) {
            return false
        }

        selectNode(node, !node.selected)

        return true
    }

    override fun onClick(node: TreeNode<T>, holder: ViewHolder) {
        if (node.isChild) {
            onToggle(node, !node.expand, holder)
        }

        nodeEventListener.onClick(node, holder)

        if (!node.isChild) {
            return
        }

        defaultRefresh(true, node)
    }

    private fun defaultRefresh(fastRefresh: Boolean = true, node: TreeNode<T>? = null) {
        coroutineScope.launch {
            refresh(fastRefresh, node)
        }
    }

    private fun selectionRefresh(old: SelectionMode, new: SelectionMode) {
        if (old == new) {
            return
        }
        coroutineScope.launch {
            if (new == SelectionMode.SINGLE || new == SelectionMode.NONE) {
                tree.selectAllNode(false)
            }
            refresh(true)
        }
    }

    override fun onLongClick(node: TreeNode<T>, holder: ViewHolder): Boolean {
        val clickResult = nodeEventListener.onLongClick(node, holder)

        if (selectNodeWhenLongClick) {
            coroutineScope.launch(Dispatchers.Main) {
                val supportChangeSelectStatus = trySelect(node)
                if (supportChangeSelectStatus) {
                    defaultRefresh(true, node)
                }
            }
        }

        return clickResult || (selectNodeWhenLongClick && selectionMode != SelectionMode.NONE)
    }

    override fun onToggle(node: TreeNode<T>, isExpand: Boolean, holder: ViewHolder) {
        node.expand = isExpand
        nodeEventListener.onToggle(node, isExpand, holder)
    }


    class ViewHolder(
        rootView: View
    ) : RecyclerView.ViewHolder(rootView)

    private inner class ItemTouchHelperCallback : ItemTouchHelper.Callback() {
        private var tempMoveNodes: Pair<TreeNode<T>, TreeNode<T>>? = null
        private var originNode: TreeNode<T>? = null
        private var lastExpandNode: TreeNode<T>? = null
        private var expandNodeDelay = 200L

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            return makeMovementFlags(
                if (supportDragging) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0,
                0
            )
        }

        // Called when hovering over a position
        override fun isLongPressDragEnabled(): Boolean = supportDragging

        // Check if we should expand the target directory node
        private fun checkExpandNode(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float
        ) {
            val targetNode = this@TreeView._adapter.getItem(target.adapterPosition)

            // Only handle directory nodes that are not expanded
            if (!targetNode.isChild || targetNode.expand) {
                lastExpandNode = null
                return
            }

            // Check if the dragged view overlaps with the target view
            val draggedView = viewHolder.itemView
            val targetView = target.itemView

            val draggedRect = draggedView.run {
                val location = IntArray(2)
                getLocationOnScreen(location)
                android.graphics.Rect(
                    location[0],
                    location[1],
                    location[0] + width,
                    location[1] + height
                )
            }

            val targetRect = targetView.run {
                val location = IntArray(2)
                getLocationOnScreen(location)
                android.graphics.Rect(
                    location[0],
                    location[1],
                    location[0] + width,
                    location[1] + height
                )
            }

            // If views overlap and this is a new target node
            if (draggedRect.intersect(targetRect) && lastExpandNode != targetNode) {
                lastExpandNode = targetNode

                // Expand the node after a delay
                coroutineScope.launch {
                    delay(expandNodeDelay)
                    // Check if we're still hovering over the same node
                    if (lastExpandNode == targetNode) {
                        targetNode.expand = true
                        refresh(true, targetNode)
                    }
                }
            }
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val tree = this@TreeView.tree
            val srcNode = this@TreeView._adapter.getItem(viewHolder.adapterPosition)
            var targetNode = this@TreeView._adapter.getItem(target.adapterPosition)
            var lastTargetNode = this@TreeView._adapter.getItem(max(target.adapterPosition - 1, 0))

            if (originNode == null) {
                originNode = srcNode.copy()
            }

            // Handle moving down case
            if (lastTargetNode == srcNode) {
                lastTargetNode = targetNode
                targetNode = this@TreeView._adapter.getItem(
                    min(target.adapterPosition + 1, this@TreeView._adapter.itemCount - 1)
                )
            }

            // Determine the actual target node based on tree structure
            targetNode = determineTargetNode(tree, lastTargetNode, targetNode)

            if (targetNode.path.startsWith(srcNode.path) && srcNode.depth < targetNode.depth) {
                return false
            }

            val canMove = binder.onMoveView(viewHolder, srcNode, target, targetNode)
            if (!canMove) return false

            tempMoveNodes = Pair(srcNode, targetNode)
            return this@TreeView._adapter.onMoveHolder(srcNode, targetNode, viewHolder, target)
        }

        // Called continuously during drag
        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return

            // Find the view we're hovering over
            val targetView = recyclerView.findChildViewUnder(
                viewHolder.itemView.x + dX,
                viewHolder.itemView.y + dY
            ) ?: return

            val target = recyclerView.getChildViewHolder(targetView)
            checkExpandNode(recyclerView, viewHolder, target, dX, dY)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)

            if (tempMoveNodes == null) {
                this@TreeView.binder.onMovedView(null, null, viewHolder)
                return
            }

            val (srcNode, targetNode) = tempMoveNodes ?: return
            val copyOfOriginNode = originNode

            coroutineScope.launch(Dispatchers.Main) {
                binder.onMovedView(srcNode, targetNode, viewHolder)
                srcNode.depth = copyOfOriginNode?.depth ?: srcNode.depth
                tree.moveNode(srcNode, targetNode)
                refresh(false)
            }

            tempMoveNodes = null
            originNode = null
            lastExpandNode = null
        }

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (viewHolder == null) return

            val srcNode = this@TreeView._adapter.getItem(viewHolder.adapterPosition)
            when (actionState) {
                ItemTouchHelper.ACTION_STATE_DRAG -> {
                    if (originNode == null) {
                        originNode = srcNode.copy()
                    }
                    binder.onMoveView(viewHolder, srcNode)
                }
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // Not implemented
        }

        private fun determineTargetNode(
            tree: Tree<T>,
            lastTargetNode: TreeNode<T>,
            targetNode: TreeNode<T>
        ): TreeNode<T> {
            // Case 1: Moving between siblings
            if (lastTargetNode.depth == targetNode.depth &&
                tree.getParentNode(lastTargetNode) == tree.getParentNode(targetNode)
            ) {
                return tree.getParentNode(targetNode) ?: targetNode
            }

            // Case 2: Moving to collapsed directory
            if (targetNode.isChild && !targetNode.expand) {
                return tree.getParentNode(targetNode) ?: targetNode
            }

            // Case 3: Moving into expanded directory
            if (lastTargetNode.depth < targetNode.depth && lastTargetNode.expand) {
                return lastTargetNode
            }

            // Case 4: Moving across different depth levels
            if (lastTargetNode.depth > targetNode.depth) {
                return handleDifferentDepthMove(tree, lastTargetNode, targetNode)
            }

            return targetNode
        }

        private fun handleDifferentDepthMove(
            tree: Tree<T>,
            lastTargetNode: TreeNode<T>,
            targetNode: TreeNode<T>
        ): TreeNode<T> {
            var parentLastTargetNode = tree.getParentNode(lastTargetNode) ?: lastTargetNode

            // Find common ancestor level
            while (parentLastTargetNode.depth > targetNode.depth) {
                val parent = tree.getParentNode(parentLastTargetNode) ?: break
                parentLastTargetNode = parent
            }

            val parentLastTargetNodeOfNull = tree.getParentNode(parentLastTargetNode)
            val parentTargetNode = tree.getParentNode(targetNode)

            // Check if nodes share same expanded parent
            if (parentLastTargetNodeOfNull != null && parentTargetNode != null &&
                parentLastTargetNodeOfNull.expand && parentLastTargetNodeOfNull == parentTargetNode
            ) {
                return parentLastTargetNodeOfNull
            }

            return targetNode
        }
    }


    private inner class Adapter(val binder: TreeViewBinder<T>) :
        ListAdapter<TreeNode<T>, ViewHolder>(binder as DiffUtil.ItemCallback<TreeNode<T>>) {

        public override fun getItem(position: Int): TreeNode<T> {
            return super.getItem(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemView = binder.createView(parent, viewType)
            return ViewHolder(itemView)
        }

        override fun getItemViewType(position: Int): Int {
            return binder.getItemViewType(getItem(position))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val rootView = this@TreeView
            val node = getItem(position)

            holder.itemView.apply {
                setOnClickListener {
                    if (isHorizontalScrolling) {
                        return@setOnClickListener
                    }
                    rootView.onClick(node, holder)
                }

                setOnLongClickListener {
                    if (!isHorizontalScrolling) {
                        return@setOnLongClickListener rootView.onLongClick(node, holder)
                    }
                    false
                }

                isLongClickable = true
            }

            binder.bindView(holder, node, rootView)

            val checkableView = binder.getCheckableView(node, holder)

            if (checkableView != null) {
                checkableView.isChecked = node.selected
            }

            if (supportHorizontalScroll) {
                holder.itemView.apply {
                    // First measure with wrap content to get actual content width
                    updateLayoutParams<ViewGroup.LayoutParams> {
                        width = LayoutParams.WRAP_CONTENT
                        height = LayoutParams.WRAP_CONTENT
                    }

                    measure(
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    )

                    val measuredWidth = measuredWidth

                    // Set width to a large value to ensure item fills available space
                    updateLayoutParams<ViewGroup.LayoutParams> {
                        width = 1000000 // Use large fixed width
                        height = measuredHeight
                    }

                    // Update max width if needed
                    if (measuredWidth > maxChildWidth) {
                        maxChildWidth = measuredWidth
                        needsWidthRefresh = true
                        post { requestLayout() }
                    }
                }
            } else {
                holder.itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = LayoutParams.MATCH_PARENT
                    height = LayoutParams.WRAP_CONTENT
                }
            }
        }

        override fun onViewAttachedToWindow(holder: ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (supportHorizontalScroll) {
                needsWidthRefresh = true
                holder.itemView.post { requestLayout() }
            }
        }

        override fun getItemId(position: Int): Long {
            return getItem(position).id.toLong()
        }

        internal fun refresh() {
            val currentData = currentList.toMutableList()
            submitList(listOf())
            submitList(currentData)
        }

        override fun onCurrentListChanged(
            previousList: MutableList<TreeNode<T>>,
            currentList: MutableList<TreeNode<T>>
        ) {
            super.onCurrentListChanged(previousList, currentList)

            for (changeNode in binder.changeNodes) {
                val changePosition = currentList.indexOf(changeNode)
                getViewHolder(changePosition)?.let { holder ->
                    onBindViewHolder(holder, changePosition)
                }
            }

            binder.changeNodes.clear()
        }

        // Only move in cache, not in tree
        fun onMoveHolder(
            srcNode: TreeNode<T>,
            targetNode: TreeNode<T>,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            srcNode.depth = if (targetNode.isChild) {
                targetNode.depth + 1
            } else {
                targetNode.depth
            }

            if (targetNode.path.startsWith(srcNode.path) && srcNode.depth < targetNode.depth) {
                return false
            }

            val currentList = currentList.toMutableList()

            Collections.swap(currentList, viewHolder.adapterPosition, target.adapterPosition)

            submitList(currentList)

            return true
        }

    }


    /**
     * Selection mode of the TreeView
     *
     * @see [TreeView.selectionMode]
     */
    enum class SelectionMode {
        /**
         * Default mode.
         *
         * No selection
         */
        NONE,

        /**
         * Single selection mode
         *
         * Only one node can be selected
         */
        SINGLE,

        /**
         * Multiple selection mode
         *
         * Multiple nodes can be selected
         */
        MULTIPLE,

        /**
         * Multiple selection mode with children
         *
         * Multiple nodes can be selected, and the children of the selected node will also be selected
         */
        MULTIPLE_WITH_CHILDREN,
    }

    private fun recalculateMaxWidth() {
        if (!supportHorizontalScroll) {
            maxChildWidth = 0
            return
        }

        var maxWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val holder = getChildViewHolder(child) as? ViewHolder ?: continue
            val position = holder.adapterPosition
            if (position == NO_POSITION) continue

            // Get the measured width including padding and margins
            val itemWidth = child.measuredWidth + child.paddingLeft + child.paddingRight
            maxWidth = maxWidth.coerceAtLeast(itemWidth)
        }

        if (maxWidth > 0) {
            maxChildWidth = maxWidth
        }
    }
}

/**
 * Binder for TreeView and nodes.
 *
 * TreeView calls this class to get the generated itemView and bind the node data to the itemView
 *
 * @see [TreeView.binder]
 */
abstract class TreeViewBinder<T : Any> : DiffUtil.ItemCallback<TreeNode<T>>() {

    private val nodeCacheHashCodes = mutableMapOf<Int, Int>()
    internal val changeNodes = CopyOnWriteArrayList<TreeNode<T>>()

    /**
     * like [RecyclerView.Adapter.onCreateViewHolder].
     *
     * Simply provide View. No need to provide a ViewHolder.
     *
     * @see [RecyclerView.Adapter.onCreateViewHolder]
     */
    abstract fun createView(parent: ViewGroup, viewType: Int): View

    /**
     * like [RecyclerView.Adapter.onBindViewHolder]
     *
     * The adapter calls this method to display the data in the node to the view
     *
     *
     * @param [node] target node
     * @param [listener] The root event listener of the TreeView.
     *
     * If you need to override the itemView's click event, from node's selected status, etc.
     * call this event listener after you have completed your action.
     *
     * Otherwise the listener you set will not work either.
     *
     * @see [RecyclerView.Adapter.onBindViewHolder]
     */
    abstract fun bindView(
        holder: TreeView.ViewHolder,
        node: TreeNode<T>,
        listener: TreeNodeEventListener<T>
    )

    /**
     * like [RecyclerView.Adapter.getItemViewType]
     *
     * For inter node data, the type (whether it is a leaf node or not) varies and different layouts may need to be provided.
     *
     * You can return different numbers and these return values are mapped in the viewType in the [createView]
     *
     * @see [RecyclerView.Adapter.getItemViewType]
     * @see [createView]
     */
    abstract fun getItemViewType(node: TreeNode<T>): Int

    /**
     * like [ItemTouchHelper.Callback.clearView]
     *
     * Called when the view is released after dragging.
     *
     * You can override this method to do some operations on the view, such as set background color, etc.
     **
     *
     * @see [ItemTouchHelper.Callback.clearView]
     */
    open fun onMovedView(
        srcNode: TreeNode<T>? = null,
        targetNode: TreeNode<T>? = null,
        holder: RecyclerView.ViewHolder
    ) {
    }

    /**
     * like [ItemTouchHelper.Callback.onSelectedChanged]
     *
     * Called when the view is selected after dragging.
     *
     * You can override this method to do some operations on the view, such as set background color, etc.
     */
    open fun onMoveView(
        srcHolder: RecyclerView.ViewHolder,
        srcNode: TreeNode<T>,
        targetHolder: RecyclerView.ViewHolder? = null,
        targetNode: TreeNode<T>? = null,
    ): Boolean {
        return true
    }

    /**
     * Get the checkable view in the itemView.
     *
     * The TreeView will automatically check the node is selected. If the node is selected, the TreeView will call the [Checkable.setChecked] and [bindView].
     *
     *
     * @see [TreeNodeEventListener.onLongClick]
     * @param [node] target node
     * @param [holder] The ViewHolder of the node
     */
    open fun getCheckableView(node: TreeNode<T>, holder: TreeView.ViewHolder): Checkable? {
        return null
    }

    @SuppressLint("DiffUtilEquals")
    final override fun areContentsTheSame(
        oldItem: TreeNode<T>,
        newItem: TreeNode<T>
    ): Boolean {
        val isSame =
            oldItem.id == newItem.id && oldItem == newItem && oldItem.data == newItem.data
        if (!isSame) {
            return false
        }
        val oldHash = nodeCacheHashCodes[newItem.id] ?: 0
        val newHash = newItem.hashCode()
        if (oldHash != newHash) {
            nodeCacheHashCodes[newItem.id] = newHash
            changeNodes.add(newItem)
        }
        return true
    }

    final override fun areItemsTheSame(
        oldItem: TreeNode<T>,
        newItem: TreeNode<T>
    ): Boolean {
        return oldItem.id == newItem.id
    }
}


class EmptyTreeNodeEventListener : TreeNodeEventListener<Any>

/**
 * Event listener interface for tree nodes.
 *
 * Currently supported, [onClick], [onLongClick], and [onToggle]
 */
interface TreeNodeEventListener<T : Any> {

    /**
     * Called when a node has been clicked.
     *
     * @param node Clicked node
     * @param holder Node binding of the holder
     */
    fun onClick(node: TreeNode<T>, holder: TreeView.ViewHolder) {}

    /**
     * Called when a node has been clicked and held.
     *
     *
     * @param node Clicked node
     * @param holder Node binding of the holder
     * @return `true` if the callback consumed the long click, false otherwise.
     */
    fun onLongClick(node: TreeNode<T>, holder: TreeView.ViewHolder): Boolean {
        return false
    }

    /**
     * Called when a node is clicked when it is a child node
     *
     * @param node Clicked node
     * @param isExpand Is the node expanded
     * @param holder Node binding of the holder
     */
    fun onToggle(node: TreeNode<T>, isExpand: Boolean, holder: TreeView.ViewHolder) {}

    /**
     * Called when refresh is triggered.
     *
     * @param status The refresh status, the value is true when the refresh is triggered, and false when the refresh is completed.
     */
    fun onRefresh(status: Boolean) {}
}