package org.appdevforall.codeonthego.layouteditor.editor.palette.layouts;

import android.content.Context;
import android.graphics.Canvas;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import org.appdevforall.codeonthego.layouteditor.utils.Constants;
import org.appdevforall.codeonthego.layouteditor.utils.Utils;

/**
 * A design-time representation of a CoordinatorLayout.
 * <p>
 * This class provides visual feedback within the layout editor, such as a dashed
 * border, to indicate its boundaries and state (e.g., blueprint mode). It mirrors
 * the functionality of ConstraintLayoutDesign for a consistent look and feel.
 */
public class CoordinatorLayoutDesign extends CoordinatorLayout {
    private boolean isBlueprint;
    private boolean drawStrokeEnabled;

    public CoordinatorLayoutDesign(Context context) {
        super(context);
    }

    /**
     * Overridden to draw custom design-time visuals *after* the children have been drawn.
     * This is where we draw the dashed border for the layout itself.
     */
    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        // Let the default CoordinatorLayout draw its children first.
        super.dispatchDraw(canvas);

        // After drawing children, draw our custom dashed stroke on top if enabled.
        if (drawStrokeEnabled) {
            Utils.drawDashPathStroke(
                    this,
                    canvas,
                    isBlueprint ? Constants.BLUEPRINT_DASH_COLOR : Constants.DESIGN_DASH_COLOR);
        }
    }

    /**
     * Overridden to handle the "blueprint" mode. In blueprint mode, we don't draw
     * the standard background or children, only a dashed outline.
     */
    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isBlueprint) {
            // In blueprint mode, only draw the dashed outline and nothing else.
            Utils.drawDashPathStroke(this, canvas, Constants.BLUEPRINT_DASH_COLOR);
        } else {
            // In normal design mode, perform the standard draw operation.
            super.draw(canvas);
        }
    }

    /**
     * Toggles blueprint mode.
     *
     * @param isBlueprint true to enable blueprint mode, false for normal design mode.
     */
    public void setBlueprint(boolean isBlueprint) {
        this.isBlueprint = isBlueprint;
        invalidate(); // Redraw the view with the new mode.
    }

    /**
     * Toggles the visibility of the dashed stroke outline in design mode.
     *
     * @param enabled true to show the dashed outline.
     */
    public void setStrokeEnabled(boolean enabled) {
        this.drawStrokeEnabled = enabled;
        invalidate(); // Redraw the view.
    }
}
