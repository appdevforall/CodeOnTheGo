package org.appdevforall.codeonthego.layouteditor.editor.palette.containers;

import android.content.Context;
import android.graphics.Canvas;
import com.google.android.material.navigation.NavigationView;
import org.appdevforall.codeonthego.layouteditor.utils.Constants;
import org.appdevforall.codeonthego.layouteditor.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NavigationViewDesign extends NavigationView {

	private boolean drawStrokeEnabled;
	private boolean isBlueprint;

	private final Logger logger = LoggerFactory.getLogger(NavigationViewDesign.class);

	public NavigationViewDesign(Context context) {
		super(context);
	}

	@Override
	public void draw(Canvas canvas) {
		if (isBlueprint)
			Utils.drawDashPathStroke(this, canvas, Constants.BLUEPRINT_DASH_COLOR);
		else
			super.draw(canvas);
	}

	public void setBlueprint(boolean isBlueprint) {
		this.isBlueprint = isBlueprint;
		invalidate();
	}

	public void setStrokeEnabled(boolean enabled) {
		drawStrokeEnabled = enabled;
		invalidate();
	}

	@Override
	protected void dispatchDraw(Canvas canvas) {
		super.dispatchDraw(canvas);

		if (drawStrokeEnabled)
			Utils.drawDashPathStroke(
					this, canvas, isBlueprint ? Constants.BLUEPRINT_DASH_COLOR : Constants.DESIGN_DASH_COLOR);
	}

	@Override
	protected void onAttachedToWindow() {
		try {
			super.onAttachedToWindow();
		} catch (IllegalArgumentException e) {
			logger.error("NavigationView should be placed in a DrawerLayout", e);
		}
	}
}
