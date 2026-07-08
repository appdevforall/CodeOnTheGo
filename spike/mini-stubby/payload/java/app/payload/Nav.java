package app.payload;

import android.app.Activity;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal in-payload view-stack navigator. The shell hosts ONE plain Activity
 * with no Fragment support, so "multi-screen" means swapping child views of a
 * root FrameLayout. Screens keep their view (and thus their state) while
 * covered; back is an in-UI affordance because the payload cannot intercept
 * the host Activity's system back.
 */
public final class Nav {

    private final FrameLayout root;
    private final Deque<Screen> stack = new ArrayDeque<>();

    public Nav(Activity host) {
        this.root = new FrameLayout(host);
    }

    public FrameLayout root() {
        return root;
    }

    public Screen top() {
        return stack.peek();
    }

    /** Clears the stack and installs a new base screen. */
    public void reset(Screen base) {
        stack.clear();
        push(base);
    }

    public void push(Screen s) {
        stack.push(s);
        show(s);
    }

    /** Pops the top screen; no-op at the base of the stack. */
    public void pop() {
        if (stack.size() <= 1) {
            return;
        }
        stack.pop();
        show(stack.peek());
    }

    private void show(Screen s) {
        root.removeAllViews();
        root.addView(s.view(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        s.onShow();
    }
}
