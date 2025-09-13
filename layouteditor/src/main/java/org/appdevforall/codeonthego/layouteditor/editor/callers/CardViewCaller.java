package org.appdevforall.codeonthego.layouteditor.editor.callers;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

import androidx.cardview.widget.CardView;

import org.appdevforall.codeonthego.layouteditor.utils.DimensionUtil;

public class CardViewCaller {
	
	public static void setCardElevation(View target, String value, Context context) {
		((CardView) target).setCardElevation(DimensionUtil.parse(value, context));
	}
	
	public static void setCardCornerRadius(View target, String value, Context context) {
		((CardView) target).setRadius(DimensionUtil.parse(value, context));
	}
	
	public static void setCardUseCompatPadding(View target, String value, Context context) {
		((CardView) target).setUseCompatPadding(value.equals("true"));
	}
	
	public static void setCardBackgroundColor(View target, String value, Context context) {
		((CardView) target).setCardBackgroundColor(Color.parseColor(value));
	}
}