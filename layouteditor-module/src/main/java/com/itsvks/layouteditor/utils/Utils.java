package com.itsvks.layouteditor.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;
import com.itsvks.layouteditor.R.string;
import com.itsvks.layouteditor.vectormaster.VectorMasterDrawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class Utils {
  /**
   * This method is used to convert the input into the equivalent dip value.
   */
  public static int pxToDp(@NonNull Context context, int input) {
    return (int)
      TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, input, context.getResources().getDisplayMetrics());
  }

  public static boolean isDarkMode(@NonNull Context context) {
    int uiMode =
      context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return uiMode == Configuration.UI_MODE_NIGHT_YES;
  }

  public static int getOnSurfaceColor(Context context) {
    return MaterialColors.getColor(context, R.attr.colorOnSurface, 0);
  }

  public static int getSurfaceColor(Context context) {
    return MaterialColors.getColor(context, R.attr.colorSurface, 0);
  }

  public static int getPrimaryColor(Context context) {
    return MaterialColors.getColor(context, R.attr.colorPrimary, 0);
  }

  public static int getSecondaryColor(Context context) {
    return MaterialColors.getColor(context, R.attr.colorSecondary, 0);
  }

  public static void drawDashPathStroke(@NonNull View view, @NonNull Canvas canvas, @NonNull Paint paint) {
    paint.setAntiAlias(true);
    paint.setStyle(Paint.Style.STROKE);
    paint.setPathEffect(new DashPathEffect(new float[]{10, 7}, 0));
    canvas.drawRect(0, 0, view.getWidth(), view.getHeight(), paint);
  }

  public static void drawDashPathStroke(View view, Canvas canvas) {
    drawDashPathStroke(view, canvas, getDefaultPaint(view.getContext()));
  }

  public static void drawDashPathStroke(@NonNull View view, Canvas canvas, int paintColor) {
    Paint paint = getDefaultPaint(view.getContext());
    paint.setColor(paintColor);
    drawDashPathStroke(view, canvas, paint);
  }

  @NonNull
  private static Paint getDefaultPaint(Context context) {
    Paint paint = new Paint();
    paint.setColor(isDarkMode(context) ? Color.WHITE : getOnSurfaceColor(context));
    paint.setStrokeWidth(pxToDp(context, 2));
    return paint;
  }

  public static boolean saveBitmapAsImageToGallery(@NonNull Context context, Bitmap bitmap, String title) {
    String savedImageURL = null;

    // Get the directory for the user's public pictures directory.
    File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
    // Create a new directory for your app
    File appDir = new File(path, context.getString(string.app_name));
    if (!appDir.exists()) {
      appDir.mkdir();
    }

    // Generate a unique file name for your image
    String fileName =
      context.getString(string.app_name).concat(" ").concat(title).concat(" ")
        + new Date().getTime()
        + ".jpg";
    fileName = fileName.replaceAll(" ", "_").toLowerCase(Locale.getDefault());
    File file = new File(appDir, fileName);

    // Save the image to the directory
    try {
      OutputStream fOut = new FileOutputStream(file);
      bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fOut);
      fOut.flush();
      fOut.close();
    } catch (IOException e) {
      e.printStackTrace();
      Log.d("MediaUtils", "Error saving image to gallery: " + e.getMessage());
      return false;
    }

    // Add the image to the Android gallery
    try {
      ContentResolver contentResolver = context.getContentResolver();
      ContentValues values = new ContentValues();
      values.put(MediaStore.Images.Media.TITLE, fileName);
      values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
      values.put(
        MediaStore.Images.Media.DESCRIPTION,
        "Image saved from ".concat(context.getString(string.app_name)));
      values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis());
      values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      values.put(MediaStore.Images.Media.ORIENTATION, 0);
      values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());

      Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      savedImageURL = uri.toString();

      Log.d("MediaUtils", "Image saved to gallery: " + savedImageURL);
      return true;
    } catch (Exception e) {
      e.printStackTrace();
      Log.d("MediaUtils", "Error saving image to gallery: " + e.getMessage());
      return false;
    }
  }

  public static int getScreenWidth() {
    DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
    return displayMetrics.widthPixels;
  }

  public static VectorMasterDrawable getVectorDrawableAsync(Context context, Uri uri) {
    Callable<VectorMasterDrawable> callable =
      new Callable<VectorMasterDrawable>() {
        @Override
        public VectorMasterDrawable call() throws Exception {
          // Load the drawable from file
          InputStream is = context.getContentResolver().openInputStream(uri);
          VectorMasterDrawable drawable = new VectorMasterDrawable(context);
          drawable.setInputStream(is);
          is.close();
          return drawable;
        }
      };

    FutureTask<VectorMasterDrawable> futureTask = new FutureTask<>(callable);
    new Thread(futureTask).start();

    try {
      VectorMasterDrawable drawable = futureTask.get();
      return drawable;
    } catch (ExecutionException | InterruptedException e) {
      e.printStackTrace();
      return null;
    }
  }

  @NonNull
  public static String toCamelCase(@NonNull String s) {
    int ctr = 0;
    int n = s.length();
    char[] ch = s.toCharArray();
    int c = 0;

    for (int i = 0; i < n; i++) {
      if (i == 0) {
        ch[i] = Character.toLowerCase(ch[i]);
      }
      if (ch[i] == ' ') {
        ctr++;
        ch[i+1] = Character.toUpperCase(ch[i+1]);
        continue;
      } else {
        ch[c++] = ch[i];
      }
    }

    return String.valueOf(ch, 0, n - ctr);
  }
}
