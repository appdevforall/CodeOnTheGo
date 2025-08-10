package org.appdevforall.codeonthego.layouteditor.editor.palette.layouts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import org.appdevforall.codeonthego.layouteditor.utils.Utils;
import org.appdevforall.codeonthego.layouteditor.utils.Constants;

public class ConstraintLayoutDesign extends ConstraintLayout {
  private boolean drawStrokeEnabled;
  private boolean isBlueprint;

  private Paint linePaint;
  private Paint fillPaint;

  private final int PARENT_ID = ConstraintLayout.LayoutParams.PARENT_ID;
  private final int LEFT = 1;
  private final int RIGHT = 2;
  private final int TOP = 3;
  private final int BOTTOM = 4;

  public ConstraintLayoutDesign(Context context) {
    super(context);

    linePaint = new Paint();
    linePaint.setColor(Color.LTGRAY);
    linePaint.setStrokeWidth(2);
    linePaint.setAntiAlias(true);
    linePaint.setStyle(Paint.Style.STROKE);

    fillPaint = new Paint();
    fillPaint.setColor(Color.LTGRAY);
    fillPaint.setAntiAlias(true);
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    if (drawStrokeEnabled) {
      drawBindings(canvas);
    }

    super.dispatchDraw(canvas);

    if (drawStrokeEnabled)
      Utils.drawDashPathStroke(
          this, canvas, isBlueprint ? Constants.BLUEPRINT_DASH_COLOR : Constants.DESIGN_DASH_COLOR);
  }

  @Override
  public void draw(Canvas canvas) {
    if (isBlueprint) Utils.drawDashPathStroke(this, canvas, Constants.BLUEPRINT_DASH_COLOR);
    else super.draw(canvas);
  }

  public void setBlueprint(boolean isBlueprint) {
    this.isBlueprint = isBlueprint;
    invalidate();
  }

  public void setStrokeEnabled(boolean enabled) {
    drawStrokeEnabled = enabled;
    invalidate();
  }

  private void drawBindings(Canvas canvas) {
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);
      ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) view.getLayoutParams();
      
      // Draw connection lines for the view's constraints
      if (params.leftToLeft != -1) {
        int targetId = params.leftToLeft;
        drawConstraintLine(canvas, view, targetId, LEFT, LEFT);
      }
      if (params.leftToRight != -1) {
        int targetId = params.leftToRight;
        drawConstraintLine(canvas, view, targetId, LEFT, RIGHT);
      }
      if (params.rightToLeft != -1) {
        int targetId = params.rightToLeft;
        drawConstraintLine(canvas, view, targetId, RIGHT, LEFT);
      }
      if (params.rightToRight != -1) {
        int targetId = params.rightToRight;
        drawConstraintLine(canvas, view, targetId, RIGHT, RIGHT);
      }
      if (params.topToTop != -1) {
        int targetId = params.topToTop;
        drawConstraintLine(canvas, view, targetId, TOP, TOP);
      }
      if (params.topToBottom != -1) {
        int targetId = params.topToBottom;
        drawConstraintLine(canvas, view, targetId, TOP, BOTTOM);
      }
      if (params.bottomToTop != -1) {
        int targetId = params.bottomToTop;
        drawConstraintLine(canvas, view, targetId, BOTTOM, TOP);
      }
      if (params.bottomToBottom != -1) {
        int targetId = params.bottomToBottom;
        drawConstraintLine(canvas, view, targetId, BOTTOM, BOTTOM);
      }
    }
  }
  
  private void drawConstraintLine(Canvas canvas, View view, int targetId, int startSide, int endSide) {
    View targetView = targetId == PARENT_ID ? this : findViewById(targetId);
    if (targetView == null) return;
    
    int x1 = 0, y1 = 0, x2 = 0, y2 = 0;
    
    // Get source coordinates
    switch (startSide) {
      case LEFT:
        x1 = view.getLeft();
        y1 = view.getTop() + view.getHeight() / 2;
        break;
      case RIGHT:
        x1 = view.getRight();
        y1 = view.getTop() + view.getHeight() / 2;
        break;
      case TOP:
        x1 = view.getLeft() + view.getWidth() / 2;
        y1 = view.getTop();
        break;
      case BOTTOM:
        x1 = view.getLeft() + view.getWidth() / 2;
        y1 = view.getBottom();
        break;
    }
    
    // Get target coordinates
    switch (endSide) {
      case LEFT:
        x2 = targetView.getLeft();
        y2 = targetView.getTop() + targetView.getHeight() / 2;
        break;
      case RIGHT:
        x2 = targetView.getRight();
        y2 = targetView.getTop() + targetView.getHeight() / 2;
        break;
      case TOP:
        x2 = targetView.getLeft() + targetView.getWidth() / 2;
        y2 = targetView.getTop();
        break;
      case BOTTOM:
        x2 = targetView.getLeft() + targetView.getWidth() / 2;
        y2 = targetView.getBottom();
        break;
    }
    
    // Draw the constraint line
    Paint constraintPaint = new Paint();
    constraintPaint.setColor(Color.DKGRAY);
    constraintPaint.setStrokeWidth(2);
    constraintPaint.setStyle(Paint.Style.STROKE);
    canvas.drawLine(x1, y1, x2, y2, constraintPaint);
  }

  private void drawHorArrow(Canvas canvas, int x, int y, int x2, int y2) {
    int width = x2 - x;
    int step = 10;
    int height = 10;

    for (int i = 0; i < width; i += step) {
      // line(x + i, y, x + i + step, y + step);
      canvas.drawLine(x + i, y - height / 2, x + i + step, y + height / 2, linePaint);
      canvas.drawLine(x + i + step, y - height / 2, x + i + step, y + height / 2, linePaint);
    }
  }

  private void drawVerArrow(Canvas canvas, int x, int y, int x2, int y2) {
    int height = y2 - y;
    int step = 10;
    int width = 10;

    for (int i = 0; i < height; i += step) {
      canvas.drawLine(x - width / 2, y + i, x + width / 2, y + i + step, linePaint);
      canvas.drawLine(x - width / 2, y + i + step, x + width / 2, y + i + step, linePaint);
    }
  }
}
