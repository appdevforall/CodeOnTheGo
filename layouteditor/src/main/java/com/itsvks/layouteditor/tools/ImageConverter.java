package com.itsvks.layouteditor.tools;

import android.content.res.Resources;
import android.graphics.Bitmap;
import com.itsvks.layouteditor.managers.ProjectManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class ImageConverter {

  public static void convertToDrawableDpis(
      String name, Bitmap originalImage, List<String> selectedDpis) throws IOException {

    String[] dpis = {"ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    int[] sizes = {36, 48, 72, 96, 144, 192}; // Corresponding sizes for each DPI

    for (int i = 0; i < selectedDpis.size(); i++) {
      String dpi = selectedDpis.get(i);
      int dpiIndex = Arrays.asList(dpis).indexOf(dpi);
      if (dpiIndex == -1) {
        throw new IllegalArgumentException("Unsupported DPI: " + dpi);
      }
      // Calculate the new width and height for the desired DPI
      int width = (int) (sizes[dpiIndex] * Resources.getSystem().getDisplayMetrics().density);
      int height = (int) (sizes[dpiIndex] * Resources.getSystem().getDisplayMetrics().density);

      // Create new resized image bitmap
      Bitmap resizedImage = Bitmap.createScaledBitmap(originalImage, width, height, true);

      // Save the resized image to the appropriate DPI folder
      String outputDirectoryPath =
          ProjectManager.getInstance().getOpenedProject().getPath() + "/drawable-" + dpi;
      File outputDirectory = new File(outputDirectoryPath);
      outputDirectory.mkdirs();
      FileOutputStream outputStream =
          new FileOutputStream(outputDirectory.getAbsolutePath() + "/".concat(name));
      resizedImage.compress(
          name.endsWith("png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
          100,
          outputStream);
      outputStream.close();
    }
  }
}
