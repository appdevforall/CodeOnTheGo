package com.itsvks.layouteditor.editor.callers;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.itsvks.layouteditor.utils.DimensionUtil;

import java.util.HashMap;

public class FABCaller {
	
	private static final HashMap<String, Integer> sizeMap = new HashMap<>();
	static {
		sizeMap.put("auto", FloatingActionButton.SIZE_AUTO);
		sizeMap.put("mini", FloatingActionButton.SIZE_MINI);
		sizeMap.put("normal", FloatingActionButton.SIZE_NORMAL);
	}
	
	public static void setSize(View target, String value, Context context) {
		((FloatingActionButton) target).setSize(sizeMap.get(value));
	}
	
    public static void setCustomSize(View target, String value, Context context) {
		((FloatingActionButton) target).setCustomSize((int) DimensionUtil.parse(value, context));
	}

	public static void setCompatElevation(View target, String value, Context context) {
		((FloatingActionButton) target).setCompatElevation(DimensionUtil.parse(value, context));
	}
	
	public static void setBackgroundColor(View target, String value, Context context) {
		((FloatingActionButton) target).setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(value)));
	}
}