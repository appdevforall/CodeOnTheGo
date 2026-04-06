package com.unnamed.b.atv.view;

import android.annotation.SuppressLint;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.unnamed.b.atv.model.TreeNode;

class NodeTouchHandler implements View.OnTouchListener {

    private final TreeNode node;
    private final View view;
    private final TreeNode.TreeNodeDragListener defaultDragListener;
    private final GestureDetector gestureDetector;

    private boolean isAwaitingDrag = false;

    NodeTouchHandler(TreeNode node, View view, TreeNode.TreeNodeDragListener defaultDragListener) {
        this.node = node;
        this.view = view;
        this.defaultDragListener = defaultDragListener;

        this.gestureDetector = new GestureDetector(view.getContext(), new GestureListener());
        this.gestureDetector.setIsLongpressEnabled(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetTouchState();
                break;

            case MotionEvent.ACTION_MOVE:
                handleMove();
                break;
        }

        return true;
    }

    private void resetTouchState() {
        isAwaitingDrag = false;
        view.setPressed(false);
    }

    private void handleMove() {
        if (!isAwaitingDrag) return;

        isAwaitingDrag = false;
        dispatchDrag();
    }

    private void dispatchDrag() {
        TreeNode.TreeNodeDragListener listener = node.getDragListener() != null
                ? node.getDragListener()
                : defaultDragListener;

        if (listener != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onStartDrag(node, node.getValue());
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            view.setPressed(true);
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            view.performClick();
            return true;
        }

        @Override
        public void onLongPress(@NonNull MotionEvent e) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            view.performLongClick();
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                isAwaitingDrag = true;
                return true;
            }
            return super.onDoubleTapEvent(e);
        }
    }
}
