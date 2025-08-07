package com.itsvks.layouteditor.editor.palette.legacy;

import android.graphics.Color;
import android.graphics.Paint;
import android.widget.ListView;
import android.content.Context;
import android.graphics.Canvas;
import com.itsvks.layouteditor.utils.Constants;
import com.itsvks.layouteditor.utils.Utils;

public class ListViewDesign extends ListView {

  private boolean drawStrokeEnabled;
  private boolean isBlueprint;
  private Paint previewPaint;

  public ListViewDesign(Context context) {
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

    int numItems = 3;
    int itemHeight = getHeight() / numItems;

    for (int i = 0; i < numItems; i++) {
      // draw item placeholder
      String text = "ListView Item " + (i + 1);
      float textWidth = previewPaint.measureText(text);
      float textHeight = previewPaint.descent() - previewPaint.ascent();
      float xPos = (getWidth() - textWidth) / 2;
      float yPos = i * itemHeight + (itemHeight - textHeight) / 2 - previewPaint.ascent();
      canvas.drawText(text, xPos, yPos, previewPaint);
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
