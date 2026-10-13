/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.inflater.vectormaster.models;

import android.graphics.Canvas;
import android.graphics.Matrix;
import java.util.ArrayList;
import org.appdevforall.codeonthego.inflater.vectormaster.DefaultValues;
import org.appdevforall.codeonthego.inflater.vectormaster.utilities.Utils;

public class GroupModel {
	private String name;

	private float rotation;
	private float pivotX, pivotY;
	private float scaleX, scaleY;
	private float translateX, translateY;

	private Matrix scaleMatrix;
	private Matrix originalTransformMatrix;
	private Matrix finalTransformMatrix;
	private GroupModel parent;

	private ArrayList<GroupModel> groupModels;
	private ArrayList<PathModel> pathModels;
	private ArrayList<ClipPathModel> clipPathModels;

	public GroupModel() {
		rotation = DefaultValues.GROUP_ROTATION;
		pivotX = DefaultValues.GROUP_PIVOT_X;
		pivotY = DefaultValues.GROUP_PIVOT_Y;
		scaleX = DefaultValues.GROUP_SCALE_X;
		scaleY = DefaultValues.GROUP_SCALE_Y;
		translateX = DefaultValues.GROUP_TRANSLATE_X;
		translateY = DefaultValues.GROUP_TRANSLATE_Y;

		groupModels = new ArrayList<>();
		pathModels = new ArrayList<>();
		clipPathModels = new ArrayList<>();
	}

	public void addClipPathModel(ClipPathModel clipPathModel) {
		clipPathModels.add(clipPathModel);
	}

	public void addGroupModel(GroupModel groupModel) {
		groupModels.add(groupModel);
	}

	public void addPathModel(PathModel pathModel) {
		pathModels.add(pathModel);
	}

	public void buildTransformMatrix() {

		originalTransformMatrix = new Matrix();

		originalTransformMatrix.postScale(scaleX, scaleY, pivotX, pivotY);
		originalTransformMatrix.postRotate(rotation, pivotX, pivotY);
		originalTransformMatrix.postTranslate(translateX, translateY);

		if (parent != null) {
			originalTransformMatrix.postConcat(parent.getOriginalTransformMatrix());
		}

		for (GroupModel groupModel : groupModels) {
			groupModel.buildTransformMatrix();
		}
	}

	public void drawPaths(Canvas canvas) {
		for (ClipPathModel clipPathModel : clipPathModels) {
			canvas.clipPath(clipPathModel.getPath());
		}
		for (GroupModel groupModel : groupModels) {
			groupModel.drawPaths(canvas);
		}
		for (PathModel pathModel : pathModels) {
			if (pathModel.isFillAndStroke()) {
				pathModel.makeFillPaint();
				canvas.drawPath(pathModel.getPath(), pathModel.getPathPaint());
				pathModel.makeStrokePaint();
				canvas.drawPath(pathModel.getPath(), pathModel.getPathPaint());
			} else {
				canvas.drawPath(pathModel.getPath(), pathModel.getPathPaint());
			}
		}
	}

	public void drawPaths(Canvas canvas, float offsetX, float offsetY, float scaleX, float scaleY) {
		for (ClipPathModel clipPathModel : clipPathModels) {
			canvas.clipPath(clipPathModel.getScaledAndOffsetPath(offsetX, offsetY, scaleX, scaleY));
		}
		for (GroupModel groupModel : groupModels) {
			groupModel.drawPaths(canvas, offsetX, offsetY, scaleX, scaleY);
		}
		for (PathModel pathModel : pathModels) {
			if (pathModel.isFillAndStroke()) {
				pathModel.makeFillPaint();
				canvas.drawPath(
						pathModel.getScaledAndOffsetPath(offsetX, offsetY, scaleX, scaleY),
						pathModel.getPathPaint());
				pathModel.makeStrokePaint();
				canvas.drawPath(
						pathModel.getScaledAndOffsetPath(offsetX, offsetY, scaleX, scaleY),
						pathModel.getPathPaint());
			} else {
				canvas.drawPath(
						pathModel.getScaledAndOffsetPath(offsetX, offsetY, scaleX, scaleY),
						pathModel.getPathPaint());
			}
		}
	}

	public ClipPathModel getClipPathModelByName(String name) {
		ClipPathModel cModel = null;
		for (ClipPathModel clipPathModel : getClipPathModels()) {
			if (Utils.isEqual(clipPathModel.getName(), name)) {
				return clipPathModel;
			}
		}
		for (GroupModel groupModel : getGroupModels()) {
			cModel = groupModel.getClipPathModelByName(name);
			if (cModel != null && Utils.isEqual(cModel.getName(), name))
				return cModel;
		}
		return cModel;
	}

	public ArrayList<ClipPathModel> getClipPathModels() {
		return clipPathModels;
	}

	public GroupModel getGroupModelByName(String name) {
		GroupModel grpModel = null;
		for (GroupModel groupModel : groupModels) {
			if (Utils.isEqual(groupModel.getName(), name)) {
				grpModel = groupModel;
				return grpModel;
			} else {
				grpModel = groupModel.getGroupModelByName(name);
				if (grpModel != null)
					return grpModel;
			}
		}
		return grpModel;
	}

	public ArrayList<GroupModel> getGroupModels() {
		return groupModels;
	}

	public String getName() {
		return name;
	}

	public Matrix getOriginalTransformMatrix() {
		return originalTransformMatrix;
	}

	public GroupModel getParent() {
		return parent;
	}

	public PathModel getPathModelByName(String name) {
		PathModel pModel = null;
		for (PathModel pathModel : pathModels) {
			if (Utils.isEqual(pathModel.getName(), name)) {
				return pathModel;
			}
		}
		for (GroupModel groupModel : groupModels) {
			pModel = groupModel.getPathModelByName(name);
			if (pModel != null && Utils.isEqual(pModel.getName(), name))
				return pModel;
		}
		return pModel;
	}

	public ArrayList<PathModel> getPathModels() {
		return pathModels;
	}

	public float getPivotX() {
		return pivotX;
	}

	public float getPivotY() {
		return pivotY;
	}

	public float getRotation() {
		return rotation;
	}

	public float getScaleX() {
		return scaleX;
	}

	public float getScaleY() {
		return scaleY;
	}

	public float getTranslateX() {
		return translateX;
	}

	public float getTranslateY() {
		return translateY;
	}

	public void scaleAllPaths(Matrix scaleMatrix) {
		this.scaleMatrix = scaleMatrix;
		finalTransformMatrix = new Matrix(originalTransformMatrix);
		finalTransformMatrix.postConcat(scaleMatrix);
		for (GroupModel groupModel : groupModels) {
			groupModel.scaleAllPaths(scaleMatrix);
		}
		for (PathModel pathModel : pathModels) {
			pathModel.transform(finalTransformMatrix);
		}
		for (ClipPathModel clipPathModel : clipPathModels) {
			clipPathModel.transform(finalTransformMatrix);
		}
	}

	public void scaleAllStrokeWidth(float ratio) {
		for (GroupModel groupModel : groupModels) {
			groupModel.scaleAllStrokeWidth(ratio);
		}
		for (PathModel pathModel : pathModels) {
			pathModel.setStrokeRatio(ratio);
		}
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setParent(GroupModel parent) {
		this.parent = parent;
	}

	public void setPivotX(float pivotX) {
		this.pivotX = pivotX;
	}

	public void setPivotY(float pivotY) {
		this.pivotY = pivotY;
	}

	public void setRotation(float rotation) {
		this.rotation = rotation;
		updateAndScalePaths();
	}

	public void setScaleX(float scaleX) {
		this.scaleX = scaleX;
		updateAndScalePaths();
	}

	public void setScaleY(float scaleY) {
		this.scaleY = scaleY;
		updateAndScalePaths();
	}

	public void setTranslateX(float translateX) {
		this.translateX = translateX;
		updateAndScalePaths();
	}

	public void setTranslateY(float translateY) {
		this.translateY = translateY;
		updateAndScalePaths();
	}

	public void updateAndScalePaths() {
		if (scaleMatrix != null) {
			buildTransformMatrix();
			scaleAllPaths(scaleMatrix);
		}
	}
}
