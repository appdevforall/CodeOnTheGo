package com.itsvks.layouteditor.editor.callers.parentcallers;

import android.content.Context;
import android.view.View;
import android.widget.RelativeLayout;

import com.itsvks.layouteditor.managers.IdManager;
import com.itsvks.layouteditor.utils.DimensionUtil;

public class RelativeLayoutCaller {
	
	public static void setLayoutBelow(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.BELOW, id);
		target.requestLayout();
	}
	
	public static void setLayoutAbove(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.ABOVE, id);
		target.requestLayout();
	}
	
	public static void setLayoutToLeftOf(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.LEFT_OF, id);
		target.requestLayout();
	}
	
	public static void setLayoutToRightOf(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.RIGHT_OF, id);
		target.requestLayout();
	}
	
	public static void setLayoutAlignLeft(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.ALIGN_LEFT, id);
		target.requestLayout();
	}
	
	public static void setLayoutAlignRight(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.ALIGN_RIGHT, id);
		target.requestLayout();
	}
	
	public static void setLayoutAlignTop(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.ALIGN_TOP, id);
		target.requestLayout();
	}
	
	public static void setLayoutAlignBottom(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.ALIGN_BOTTOM, id);
		target.requestLayout();
	}
	
	public static void setLayoutAlignParentLeft(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		
		if(value.equals("true")) {
			params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
		}
		else {
			params.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
		}
		
		target.requestLayout();
	}
	
	public static void setLayoutAlignParentRight(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();

		if(value.equals("true")) {
			params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
		}
		else {
			params.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
		}

		target.requestLayout();
	}
	
	public static void setLayoutAlignParentTop(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();

		if(value.equals("true")) {
			params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		}
		else {
			params.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
		}

		target.requestLayout();
	}
	
	public static void setLayoutAlignParentBottom(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();

		if(value.equals("true")) {
			params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		}
		else {
			params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		}

		target.requestLayout();
	}
	
	public static void setLayoutAlignBaseline(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		int id = IdManager.getViewId(value);
		params.addRule(RelativeLayout.ALIGN_BASELINE, id);
		target.requestLayout();
	}
	
	public static void setLayoutCenterHorizontal(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();
		
		if(value.equals("true")) {
			params.addRule(RelativeLayout.CENTER_HORIZONTAL);
		}
		else {
			params.removeRule(RelativeLayout.CENTER_HORIZONTAL);
		}
		
		target.requestLayout();
	}
	
	public static void setLayoutCenterVertical(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();

		if(value.equals("true")) {
			params.addRule(RelativeLayout.CENTER_VERTICAL);
		}
		else {
			params.removeRule(RelativeLayout.CENTER_VERTICAL);
		}

		target.requestLayout();
	}
	
	public static void setLayoutCenterInParent(View target, String value, Context context) {
		RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) target.getLayoutParams();

		if(value.equals("true")) {
			params.addRule(RelativeLayout.CENTER_IN_PARENT);
		}
		else {
			params.removeRule(RelativeLayout.CENTER_IN_PARENT);
		}

		target.requestLayout();
	}
	
	public static void setLayoutMargin(View target, String value, Context context) {
		int margin = (int) DimensionUtil.parse(value, context);
		((RelativeLayout.LayoutParams) target.getLayoutParams()).setMargins(margin, margin, margin, margin);
		target.requestLayout();
	}

	public static void setLayoutMarginLeft(View target, String value, Context context) {
    ((RelativeLayout.LayoutParams) target.getLayoutParams()).leftMargin = (int) DimensionUtil.parse(value, context);
		target.requestLayout();
	}

	public static void setLayoutMarginRight(View target, String value, Context context) {
    ((RelativeLayout.LayoutParams) target.getLayoutParams()).rightMargin = (int) DimensionUtil.parse(value, context);
		target.requestLayout();
	}

	public static void setLayoutMarginTop(View target, String value, Context context) {
    ((RelativeLayout.LayoutParams) target.getLayoutParams()).topMargin = (int) DimensionUtil.parse(value, context);
		target.requestLayout();
	}

	public static void setLayoutMarginBottom(View target, String value, Context context) {
    ((RelativeLayout.LayoutParams) target.getLayoutParams()).bottomMargin = (int) DimensionUtil.parse(value, context);
		target.requestLayout();
	}
}