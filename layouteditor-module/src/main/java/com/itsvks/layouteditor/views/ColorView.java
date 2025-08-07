package com.itsvks.layouteditor.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;

import com.itsvks.layouteditor.R;

import com.itsvks.layouteditor.views.AlphaPatternDrawable;
import java.util.Locale;

/** ColorView extends View class. It is used to draw a colored rectangle. */
public class ColorView extends View {
  /** Alpha, red, green, blue value of the color. */
  private int a = 255, r = 255, g = 255, b = 255;

  /** Drawable object to draw transparent background. */
  private final Drawable transparent;
  /** Paint object to draw color bitmap. */
  private Paint bitmapPaint;
  /** Paint object to draw color. */
  private final Paint colorPaint;

  /**
   * Constructor for ColorView class.
   *
   * @param context The Context the view is running in, through which it can access the current
   *     theme, resources, etc.
   * @param attrs The attributes of the XML tag that is inflating the view.
   */
  public ColorView(Context context, AttributeSet attrs) {
    super(context, attrs);

    transparent = new AlphaPatternDrawable(16);

    colorPaint = new Paint();
    colorPaint.setARGB(a, r, g, b);
  }

  /**
   * This is called during layout when the size of this view has changed.
   *
   * @param w Current width of this view.
   * @param h Current height of this view.
   * @param oldw Old width of this view.
   * @param oldh Old height of this view.
   */
  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    transparent.setBounds(0, 0, w, h);
  }

  /**
   * Called when the view should render its content.
   *
   * @param canvas the Canvas object on which the view will draw.
   */
  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    transparent.draw(canvas);
    canvas.drawRect(0, 0, getWidth(), getHeight(), colorPaint);
  }

  /**
   * Set the color of the rectangle.
   *
   * @param color The color, packed as ARGB in a 32-bit int.
   */
  public void setColor(int color) {
    a = Color.alpha(color);
    r = Color.red(color);
    g = Color.green(color);
    b = Color.blue(color);

    colorPaint.setARGB(a, r, g, b);
    invalidate();
  }

  /**
   * Set the alpha value of the color.
   *
   * @param value The value of alpha.
   */
  public void setAlpha(int value) {
    a = value;
    colorPaint.setARGB(a, r, g, b);
    invalidate();
  }

  /**
   * Set the red value of the color.
   *
   * @param value The value of red.
   */
  public void setRed(int value) {
    r = value;
    colorPaint.setARGB(a, r, g, b);
    invalidate();
  }

  /**
   * Set the green value of the color.
   *
   * @param value The value of green.
   */
  public void setGreen(int value) {
    g = value;
    colorPaint.setARGB(a, r, g, b);
    invalidate();
  }

  /**
   * Set the blue value of the color.
   *
   * @param value The value of blue.
   */
  public void setBlue(int value) {
    b = value;
    colorPaint.setARGB(a, r, g, b);
    invalidate();
  }

  /**
   * Get the color.
   *
   * @return The color, packed as ARGB in a 32-bit int.
   */
  public int getColor() {
    return Color.argb(a, r, g, b);
  }
    
  /**
   * Get the inverted rgb color.
   *
   * @return The inverted color, packed as RGB in a 32-bit int.
   */
  public int getInvertedRGB() {
    return 0xFFFFFF ^ Color.rgb(r, g, b);
  }

  /**
   * Get the hex value of the color.
   *
   * @return The hex value of the color.
   */
  public String getHexColor() {
    return getHex(Color.argb(a, r, g, b));
  }

  /**
   * Get the hex value of the given color.
   *
   * @param c The color, packed as ARGB in a 32-bit int.
   * @return The hex value of the given color.
   */
  @NonNull
  private String getHex(int c) {
    return String.format(
            "%02x%02x%02x%02x", Color.alpha(c), Color.red(c), Color.green(c), Color.blue(c))
        .toUpperCase(Locale.US);
  }
}
