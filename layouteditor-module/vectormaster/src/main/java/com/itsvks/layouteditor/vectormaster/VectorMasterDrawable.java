package com.itsvks.layouteditor.vectormaster;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.itsvks.layouteditor.vectormaster.models.ClipPathModel;
import com.itsvks.layouteditor.vectormaster.models.GroupModel;
import com.itsvks.layouteditor.vectormaster.models.PathModel;
import com.itsvks.layouteditor.vectormaster.models.VectorModel;
import com.itsvks.layouteditor.vectormaster.utilities.Utils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Stack;

public class VectorMasterDrawable extends Drawable {

  private String TAG = "VECTOR_MASTER";
  private VectorModel vectorModel;
  private Context context;
  private Resources resources;
  private int resID = -1;
  private boolean isVector = false;
  private boolean useLegacyParser = true;
  private boolean useLightTheme = true;
  private float offsetX = 0.0f, offsetY = 0.0f;
  private float scaleX = 1.0f, scaleY = 1.0f;
  private Matrix scaleMatrix;
  private int width = -1, height = -1;
  private float scaleRatio, strokeRatio;
  private int left = 0, top = 0;

  private InputStream vectorStream;
  private File vectorFile;
  //    private String projectPatch;

  public VectorMasterDrawable(Context context) {
    this.context = context;
    init();
  }

  public VectorMasterDrawable(Context context, int resID) {
    this.context = context;
    this.resID = resID;
    init();
  }

  public VectorMasterDrawable(Context context, File file) {
    this.context = context;
    this.vectorFile = file;
    init();
  }

  public VectorMasterDrawable(Context context, File file, boolean useLightTheme) {
    this.context = context;
    this.vectorFile = file;
    this.useLightTheme = useLightTheme;
    init();
  }

  public VectorMasterDrawable(Context context, int resID, float offsetX, float offsetY) {
    this.context = context;
    this.resID = resID;
    this.offsetX = offsetX;
    this.offsetY = offsetY;
    init();
  }

  public VectorMasterDrawable(
      Context context, int resID, float offsetX, float offsetY, float scaleX, float scaleY) {
    this.context = context;
    this.resID = resID;
    this.offsetX = offsetX;
    this.offsetY = offsetY;
    this.scaleX = scaleX;
    this.scaleY = scaleY;
    init();
  }

  private void init() {
    try {
      resources = context.getResources();
      buildVectorModel();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void buildVectorModel() {

    if (vectorFile != null) {
      try {
        vectorStream = new FileInputStream(vectorFile);
      } catch (FileNotFoundException e) {
        e.printStackTrace();
      }
    }

    XmlPullParser xpp;
    if (vectorStream != null) {
      try {
        XmlPullParserFactory parserFactory = XmlPullParserFactory.newInstance();
        parserFactory.setNamespaceAware(true);
        xpp = parserFactory.newPullParser();
        xpp.setInput(vectorStream, "utf-8");
      } catch (XmlPullParserException xml) {
        Log.e(TAG, "Error from reading vector image");
        xml.printStackTrace();
        vectorModel = null;
        return;
      }

    } else if (resID != -1) {
      xpp = resources.getXml(resID);
    } else {
      vectorModel = null;
      return;
    }

    int tempPosition;
    PathModel pathModel = new PathModel();
    vectorModel = new VectorModel();
    GroupModel groupModel = new GroupModel();
    ClipPathModel clipPathModel = new ClipPathModel();
    Stack<GroupModel> groupModelStack = new Stack<>();

    try {
      int event = xpp.getEventType();
      while (event != XmlPullParser.END_DOCUMENT) {
        String name = xpp.getName();
        switch (event) {
          case XmlPullParser.START_TAG:
            switch (name) {
              case "vector":
                isVector = true;

                tempPosition = getAttrPosition(xpp, "viewportWidth");
                vectorModel.setViewportWidth(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.VECTOR_VIEWPORT_WIDTH);

                tempPosition = getAttrPosition(xpp, "viewportHeight");
                vectorModel.setViewportHeight(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.VECTOR_VIEWPORT_HEIGHT);

                tempPosition = getAttrPosition(xpp, "alpha");
                vectorModel.setAlpha(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.VECTOR_ALPHA);

                tempPosition = getAttrPosition(xpp, "name");
                vectorModel.setName(
                    (tempPosition != -1) ? xpp.getAttributeValue(tempPosition) : null);

                tempPosition = getAttrPosition(xpp, "width");
                vectorModel.setWidth(
                    (tempPosition != -1)
                        ? Utils.getFloatFromDimensionString(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.VECTOR_WIDTH);

                tempPosition = getAttrPosition(xpp, "height");
                vectorModel.setHeight(
                    (tempPosition != -1)
                        ? Utils.getFloatFromDimensionString(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.VECTOR_HEIGHT);
                break;
              case "path":
                pathModel = new PathModel();

                tempPosition = getAttrPosition(xpp, "name");
                pathModel.setName(
                    (tempPosition != -1) ? xpp.getAttributeValue(tempPosition) : null);

                tempPosition = getAttrPosition(xpp, "fillAlpha");
                pathModel.setFillAlpha(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_FILL_ALPHA);

                tempPosition = getAttrPosition(xpp, "fillColor");
                pathModel.setFillColor(
                    (tempPosition != -1)
                        ? Utils.getColorFromStringTheme(
                            xpp.getAttributeValue(tempPosition), useLightTheme)
                        : DefaultValues.PATH_FILL_COLOR);

                tempPosition = getAttrPosition(xpp, "fillType");
                pathModel.setFillType(
                    (tempPosition != -1)
                        ? Utils.getFillTypeFromString(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_FILL_TYPE);

                tempPosition = getAttrPosition(xpp, "pathData");
                if (xpp.getAttributeValue(tempPosition).startsWith("@string/")) {
                  //                                    Log.e("AttributeValue", "value = " +
                  // xpp.getAttributeValue(tempPosition));
                  //                                    pathModel.setPathData((tempPosition != -1) ?
                  // Utils.getPatchDataFromStrings(projectPatch, xpp, tempPosition) : null);
                  pathModel.setPathData("0");
                  isVector = false;
                } else {
                  //                                    Log.e("AttributeValue", "value = " +
                  // xpp.getAttributeValue(tempPosition));
                  pathModel.setPathData(
                      (tempPosition != -1) ? xpp.getAttributeValue(tempPosition) : null);
                }

                tempPosition = getAttrPosition(xpp, "strokeAlpha");
                pathModel.setStrokeAlpha(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_STROKE_ALPHA);

                tempPosition = getAttrPosition(xpp, "strokeColor");
                pathModel.setStrokeColor(
                    (tempPosition != -1)
                        ? Utils.getColorFromString(
                            xpp.getAttributeValue(tempPosition), useLightTheme)
                        : DefaultValues.PATH_STROKE_COLOR);

                tempPosition = getAttrPosition(xpp, "strokeLineCap");
                pathModel.setStrokeLineCap(
                    (tempPosition != -1)
                        ? Utils.getLineCapFromString(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_STROKE_LINE_CAP);

                tempPosition = getAttrPosition(xpp, "strokeLineJoin");
                pathModel.setStrokeLineJoin(
                    (tempPosition != -1)
                        ? Utils.getLineJoinFromString(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_STROKE_LINE_JOIN);

                tempPosition = getAttrPosition(xpp, "strokeMiterLimit");
                pathModel.setStrokeMiterLimit(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_STROKE_MITER_LIMIT);

                tempPosition = getAttrPosition(xpp, "strokeWidth");
                pathModel.setStrokeWidth(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_STROKE_WIDTH);

                tempPosition = getAttrPosition(xpp, "trimPathEnd");
                pathModel.setTrimPathEnd(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_TRIM_PATH_END);

                tempPosition = getAttrPosition(xpp, "trimPathOffset");
                pathModel.setTrimPathOffset(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_TRIM_PATH_OFFSET);

                tempPosition = getAttrPosition(xpp, "trimPathStart");
                pathModel.setTrimPathStart(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.PATH_TRIM_PATH_START);

                pathModel.buildPath(useLegacyParser);
                break;
              case "group":
                groupModel = new GroupModel();

                tempPosition = getAttrPosition(xpp, "name");
                groupModel.setName(
                    (tempPosition != -1) ? xpp.getAttributeValue(tempPosition) : null);

                tempPosition = getAttrPosition(xpp, "pivotX");
                groupModel.setPivotX(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_PIVOT_X);

                tempPosition = getAttrPosition(xpp, "pivotY");
                groupModel.setPivotY(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_PIVOT_Y);

                tempPosition = getAttrPosition(xpp, "rotation");
                groupModel.setRotation(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_ROTATION);

                tempPosition = getAttrPosition(xpp, "scaleX");
                groupModel.setScaleX(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_SCALE_X);

                tempPosition = getAttrPosition(xpp, "scaleY");
                groupModel.setScaleY(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_SCALE_Y);

                tempPosition = getAttrPosition(xpp, "translateX");
                groupModel.setTranslateX(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_TRANSLATE_X);

                tempPosition = getAttrPosition(xpp, "translateY");
                groupModel.setTranslateY(
                    (tempPosition != -1)
                        ? Float.parseFloat(xpp.getAttributeValue(tempPosition))
                        : DefaultValues.GROUP_TRANSLATE_Y);

                groupModelStack.push(groupModel);
                break;
              case "clip-path":
                clipPathModel = new ClipPathModel();

                tempPosition = getAttrPosition(xpp, "name");
                clipPathModel.setName(
                    (tempPosition != -1) ? xpp.getAttributeValue(tempPosition) : null);

                tempPosition = getAttrPosition(xpp, "pathData");
                if (xpp.getAttributeValue(tempPosition).startsWith("@string/")) {
                  //                                    Log.e("AttributeValue", "value = " +
                  // xpp.getAttributeValue(tempPosition));
                  //                                    clipPathModel.setPathData((tempPosition !=
                  // -1) ? Utils.getPatchDataFromStrings(projectPatch, xpp, tempPosition) : null);
                  clipPathModel.setPathData("0");
                  isVector = false;
                } else {
                  Log.e("AttributeValue", "value = " + xpp.getAttributeValue(tempPosition));
                  clipPathModel.setPathData(
                      (tempPosition != -1) ? xpp.getAttributeValue(tempPosition) : null);
                }

                clipPathModel.buildPath(useLegacyParser);
                break;
            }
            break;

          case XmlPullParser.TEXT:
            Log.i(TAG, "" + xpp.getText());
            break;
          case XmlPullParser.END_TAG:
            switch (name) {
              case "path":
                if (groupModelStack.size() == 0) {
                  vectorModel.addPathModel(pathModel);
                } else {
                  groupModelStack.peek().addPathModel(pathModel);
                }
                vectorModel.getFullpath().addPath(pathModel.getPath());
                break;
              case "clip-path":
                if (groupModelStack.size() == 0) {
                  vectorModel.addClipPathModel(clipPathModel);
                } else {
                  groupModelStack.peek().addClipPathModel(clipPathModel);
                }
                break;
              case "group":
                GroupModel topGroupModel = groupModelStack.pop();
                if (groupModelStack.size() == 0) {
                  topGroupModel.setParent(null);
                  vectorModel.addGroupModel(topGroupModel);
                } else {
                  topGroupModel.setParent(groupModelStack.peek());
                  groupModelStack.peek().addGroupModel(topGroupModel);
                }
                break;
              case "vector":
                vectorModel.buildTransformMatrices();
                break;
            }
            break;
        }
        event = xpp.next();
      }
    } catch (XmlPullParserException | IOException e) {
      e.printStackTrace();
    }
  }

  private int getAttrPosition(XmlPullParser xpp, String attrName) {
    for (int i = 0; i < xpp.getAttributeCount(); i++) {
      if (xpp.getAttributeName(i).equals(attrName)) {
        return i;
      }
    }
    return -1;
  }

  public int getResID() {
    return resID;
  }

  public void setResID(int resID) {
    this.resID = resID;
    buildVectorModel();
    scaleMatrix = null;
  }

  public void setInputStream(InputStream stream) {
    this.vectorStream = stream;
    buildVectorModel();
    scaleMatrix = null;
  }

  public boolean isUseLegacyParser() {
    return useLegacyParser;
  }

  public void setUseLegacyParser(boolean useLegacyParser) {
    this.useLegacyParser = useLegacyParser;
    buildVectorModel();
    scaleMatrix = null;
  }

  @Override
  protected void onBoundsChange(Rect bounds) {
    super.onBoundsChange(bounds);

    if (bounds.width() != 0 && bounds.height() != 0) {

      left = bounds.left;
      top = bounds.top;

      width = bounds.width();
      height = bounds.height();

      buildScaleMatrix();
      scaleAllPaths();
      scaleAllStrokes();
    }
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    if (vectorModel == null) {
      return;
    }

    if (scaleMatrix == null) {
      int temp1 = Utils.dpToPx((int) vectorModel.getWidth());
      int temp2 = Utils.dpToPx((int) vectorModel.getHeight());

      setBounds(0, 0, temp1, temp2);
    }

    setAlpha(Utils.getAlphaFromFloat(vectorModel.getAlpha()));

    if (left != 0 || top != 0) {
      int tempSaveCount = canvas.save();
      canvas.translate(left, top);
      vectorModel.drawPaths(canvas, offsetX, offsetY, scaleX, scaleY);
      canvas.restoreToCount(tempSaveCount);
    } else {
      vectorModel.drawPaths(canvas, offsetX, offsetY, scaleX, scaleY);
    }
  }

  @Override
  public void setAlpha(int i) {
    vectorModel.setAlpha(Utils.getAlphaFromInt(i));
  }

  @Override
  public void setColorFilter(ColorFilter colorFilter) {}

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public int getIntrinsicWidth() {
    return Utils.dpToPx((int) vectorModel.getWidth());
  }

  @Override
  public int getIntrinsicHeight() {
    return Utils.dpToPx((int) vectorModel.getHeight());
  }

  private void buildScaleMatrix() {
    scaleMatrix = new Matrix();

    scaleMatrix.postTranslate(
        width / 2 - vectorModel.getViewportWidth() / 2,
        height / 2 - vectorModel.getViewportHeight() / 2);

    float widthRatio = width / vectorModel.getViewportWidth();
    float heightRatio = height / vectorModel.getViewportHeight();
    float ratio = Math.min(widthRatio, heightRatio);

    scaleRatio = ratio;

    scaleMatrix.postScale(ratio, ratio, width / 2, height / 2);
  }

  private void scaleAllPaths() {
    vectorModel.scaleAllPaths(scaleMatrix);
  }

  private void scaleAllStrokes() {
    strokeRatio = Math.min(width / vectorModel.getWidth(), height / vectorModel.getHeight());
    vectorModel.scaleAllStrokeWidth(strokeRatio);
  }

  public Path getFullPath() {
    if (vectorModel != null) {
      return vectorModel.getFullpath();
    }
    return null;
  }

  public GroupModel getGroupModelByName(String name) {
    GroupModel gModel;
    for (GroupModel groupModel : vectorModel.getGroupModels()) {
      if (Utils.isEqual(groupModel.getName(), name)) {
        return groupModel;
      } else {
        gModel = groupModel.getGroupModelByName(name);
        if (gModel != null) return gModel;
      }
    }
    return null;
  }

  public PathModel getPathModelByName(String name) {
    PathModel pModel = null;
    for (PathModel pathModel : vectorModel.getPathModels()) {
      if (Utils.isEqual(pathModel.getName(), name)) {
        return pathModel;
      }
    }
    for (GroupModel groupModel : vectorModel.getGroupModels()) {
      pModel = groupModel.getPathModelByName(name);
      if (pModel != null && Utils.isEqual(pModel.getName(), name)) return pModel;
    }
    return pModel;
  }

  public ClipPathModel getClipPathModelByName(String name) {
    ClipPathModel cModel = null;
    for (ClipPathModel clipPathModel : vectorModel.getClipPathModels()) {
      if (Utils.isEqual(clipPathModel.getName(), name)) {
        return clipPathModel;
      }
    }
    for (GroupModel groupModel : vectorModel.getGroupModels()) {
      cModel = groupModel.getClipPathModelByName(name);
      if (cModel != null && Utils.isEqual(cModel.getName(), name)) return cModel;
    }
    return cModel;
  }

  public void update() {
    invalidateSelf();
  }

  public boolean isVector() {
    return isVector;
  }

  public float getScaleRatio() {
    return scaleRatio;
  }

  public float getStrokeRatio() {
    return strokeRatio;
  }

  public Matrix getScaleMatrix() {
    return scaleMatrix;
  }

  public float getOffsetX() {
    return offsetX;
  }

  public void setOffsetX(float offsetX) {
    this.offsetX = offsetX;
    invalidateSelf();
  }

  public float getOffsetY() {
    return offsetY;
  }

  public void setOffsetY(float offsetY) {
    this.offsetY = offsetY;
    invalidateSelf();
  }

  public float getScaleX() {
    return scaleX;
  }

  public void setScaleX(float scaleX) {
    this.scaleX = scaleX;
    invalidateSelf();
  }

  public float getScaleY() {
    return scaleY;
  }

  public void setScaleY(float scaleY) {
    this.scaleY = scaleY;
    invalidateSelf();
  }

  /*    public void setUseLightTheme(boolean useLightTheme) {
      this.useLightTheme = useLightTheme;
      buildVectorModel();
  }*/
}
