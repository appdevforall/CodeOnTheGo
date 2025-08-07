package com.itsvks.layouteditor.editor.palette.layouts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.RelativeLayout;
import com.itsvks.layouteditor.utils.Constants;
import com.itsvks.layouteditor.utils.Utils;

public class RelativeLayoutDesign extends RelativeLayout {

  private Paint linePaint;
  private Paint fillPaint;

  private boolean drawStrokeEnabled;
  private boolean isBlueprint;

  public RelativeLayoutDesign(Context context) {
    super(context);

    linePaint = new Paint();
    linePaint.setColor(Color.LTGRAY);
    linePaint.setStrokeWidth(Utils.pxToDp(context, 2));
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

  private void drawBindings(Canvas canvas) {
    for (int i = 0; i < getChildCount(); i++) {
      View view = getChildAt(i);

      RelativeLayout.LayoutParams par = (RelativeLayout.LayoutParams) view.getLayoutParams();

      if (par.getRule(CENTER_HORIZONTAL) == -1) {
        drawHorArrow(
            canvas,
            0,
            view.getTop() + view.getHeight() / 2,
            view.getLeft(),
            view.getTop() + view.getHeight() / 2);
        drawHorArrow(
            canvas,
            view.getRight(),
            view.getTop() + view.getHeight() / 2,
            getWidth(),
            view.getTop() + view.getHeight() / 2);
      }

      if (par.getRule(CENTER_VERTICAL) == -1) {
        drawVerArrow(
            canvas,
            view.getLeft() + view.getWidth() / 2,
            0,
            view.getLeft() + view.getWidth() / 2,
            view.getTop());
        drawVerArrow(
            canvas,
            view.getLeft() + view.getWidth() / 2,
            view.getBottom(),
            view.getLeft() + view.getWidth() / 2,
            getHeight());
      }

      if (par.getRule(CENTER_IN_PARENT) == -1) {
        drawHorArrow(
            canvas,
            0,
            view.getTop() + view.getHeight() / 2,
            view.getLeft(),
            view.getTop() + view.getHeight() / 2);
        drawHorArrow(
            canvas,
            view.getRight(),
            view.getTop() + view.getHeight() / 2,
            getWidth(),
            view.getTop() + view.getHeight() / 2);

        drawVerArrow(
            canvas,
            view.getLeft() + view.getWidth() / 2,
            0,
            view.getLeft() + view.getWidth() / 2,
            view.getTop());
        drawVerArrow(
            canvas,
            view.getLeft() + view.getWidth() / 2,
            view.getBottom(),
            view.getLeft() + view.getWidth() / 2,
            getHeight());
      }

      if (par.getRule(ABOVE) != 0) {
        View anchor = findViewById(par.getRule(ABOVE));

        if (anchor != null) drawBind(canvas, view, anchor, ABOVE);
      }

      if (par.getRule(BELOW) != 0) {
        View anchor = findViewById(par.getRule(BELOW));

        if (anchor != null) drawBind(canvas, view, anchor, BELOW);
      }

      if (par.getRule(LEFT_OF) != 0) {
        View anchor = findViewById(par.getRule(LEFT_OF));

        if (anchor != null) drawBind(canvas, view, anchor, LEFT_OF);
      }

      if (par.getRule(RIGHT_OF) != 0) {
        View anchor = findViewById(par.getRule(RIGHT_OF));

        if (anchor != null) drawBind(canvas, view, anchor, RIGHT_OF);
      }
    }
  }

  public void setStrokeEnabled(boolean enabled) {
    drawStrokeEnabled = enabled;
    invalidate();
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

  private void drawBind(Canvas canvas, View view, View anchor, int rule) {
    int x1 = 0, y1 = 0;
    int x2 = 0, y2 = 0;
    int offset = 100;

    Path path = new Path();

    switch (rule) {
      case BELOW:
        {
          x1 = view.getLeft() + view.getWidth() / 2;
          y1 = view.getTop();
          x2 = anchor.getLeft() + anchor.getWidth() / 2;
          y2 = anchor.getBottom();

          int halfX = (x1 + x2) / 2;
          int halfY = (y1 + y2) / 2;

          path.moveTo(x1, y1);
          path.cubicTo(x1, y1, x1, y1 - offset, halfX, halfY);
          path.moveTo(x2, y2);
          path.cubicTo(x2, y2, x2, y2 + offset, halfX, halfY);
          break;
        }

      case ABOVE:
        {
          x1 = view.getLeft() + view.getWidth() / 2;
          y1 = view.getBottom();
          x2 = anchor.getLeft() + anchor.getWidth() / 2;
          y2 = anchor.getTop();

          int halfX = (x1 + x2) / 2;
          int halfY = (y1 + y2) / 2;

          path.moveTo(x1, y1);
          path.cubicTo(x1, y1, x1, y1 + offset, halfX, halfY);
          path.moveTo(x2, y2);
          path.cubicTo(x2, y2, x2, y2 - offset, halfX, halfY);
          break;
        }

      case LEFT_OF:
        {
          x1 = view.getRight();
          y1 = view.getTop() + view.getHeight() / 2;
          x2 = anchor.getLeft();
          y2 = anchor.getTop() + anchor.getHeight() / 2;

          int halfX = (x1 + x2) / 2;
          int halfY = (y1 + y2) / 2;

          path.moveTo(x1, y1);
          path.cubicTo(x1, y1, x1 + offset, y1, halfX, halfY);
          path.moveTo(x2, y2);
          path.cubicTo(x2, y2, x2 - offset, y2, halfX, halfY);
          break;
        }

      case RIGHT_OF:
        {
          x1 = view.getLeft();
          y1 = view.getTop() + view.getHeight() / 2;
          x2 = anchor.getRight();
          y2 = anchor.getTop() + anchor.getHeight() / 2;

          int halfX = (x1 + x2) / 2;
          int halfY = (y1 + y2) / 2;

          path.moveTo(x1, y1);
          path.cubicTo(x1, y1, x1 - offset, y1, halfX, halfY);
          path.moveTo(x2, y2);
          path.cubicTo(x2, y2, x2 + offset, y2, halfX, halfY);
          break;
        }
    }

    canvas.drawPath(path, linePaint);
    canvas.drawCircle(x2, y2, 10, fillPaint);
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
}
