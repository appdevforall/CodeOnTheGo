package com.itsvks.layouteditor.editor.palette.layouts;

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
import com.itsvks.layouteditor.utils.Utils;
import com.itsvks.layouteditor.utils.Constants;

public class ConstraintLayoutDesign extends ConstraintLayout {
  private boolean drawStrokeEnabled;
  private boolean isBlueprint;

  private Paint linePaint;
  private Paint fillPaint;

  private final int PARENT_ID = ConstraintLayout.LayoutParams.PARENT_ID;

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
//    if (drawStrokeEnabled) {
//      drawBindings(canvas);
//    }

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
      
      
    }
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
