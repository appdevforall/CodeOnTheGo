package app.payload;

import android.os.Bundle;
import android.view.View;

/** Base class for FieldSurvey screens managed by {@link Nav}. */
public abstract class Screen {

    public static final String ROUTE_HOME = "home";
    public static final String ROUTE_SURVEY = "survey";
    public static final String ROUTE_DETAIL = "detail";

    private View view;

    /** Lazily created, then cached for the lifetime of the screen. */
    public final View view() {
        if (view == null) {
            view = createView();
        }
        return view;
    }

    protected abstract View createView();

    /** Stable route id used by saveState()/restore. */
    public abstract String route();

    /** Called every time the screen becomes the visible top of the stack. */
    public void onShow() {}

    /** Contribute framework-typed values to the cross-generation state Bundle. */
    public void onSaveState(Bundle out) {}
}
