package com.itsvks.layouteditor.editor.palette.containers;

import android.graphics.Color;
import android.graphics.Paint;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Context;
import android.graphics.Canvas;
import com.itsvks.layouteditor.utils.Constants;
import com.itsvks.layouteditor.utils.Utils;

public class RecyclerViewDesign extends RecyclerView {

  private boolean drawStrokeEnabled;
  private boolean isBlueprint;
  private Paint previewPaint;

  public RecyclerViewDesign(Context context) {
    super(context);
    init();
  }

  private void init() {
    previewPaint = new Paint();
    previewPaint.setColor(Color.GRAY);
    previewPaint.setStyle(Paint.Style.FILL);
    previewPaint.setTextSize(35);
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    super.dispatchDraw(canvas);

    int numColumns = 3; // number of columns in the grid
    int itemWidth = getWidth() / numColumns;
    int itemHeight = getHeight() / numColumns;
    int x = 0;
    int y = 0;

    for (int i = 1; i <= numColumns * numColumns; i++) {
      // draw item placeholder
      String text = "item " + i;
      float textWidth = previewPaint.measureText(text);
      float textHeight = previewPaint.descent() - previewPaint.ascent();
      float xPos = x + (itemWidth - textWidth) / 2;
      float yPos = y + (itemHeight - textHeight) / 2 - previewPaint.ascent();
      canvas.drawText(text, xPos, yPos, previewPaint);

      // update x and y position for next item
      x += itemWidth;
      if (i % numColumns == 0) {
        x = 0;
        y += itemHeight;
      }
    }

    if (drawStrokeEnabled)
      Utils.drawDashPathStroke(
          this, canvas, isBlueprint ? Constants.BLUEPRINT_DASH_COLOR : Constants.DESIGN_DASH_COLOR);
  }

  public void setStrokeEnabled(boolean enabled) {
    drawStrokeEnabled = enabled;
    invalidate();
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
