package app.payload.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Custom-Canvas sparkline of visit scores (stressors 1, 4, 8).
 *
 * THIS CLASS IS REFERENCED BY NAME IN screen_home.xml — the whole point of the
 * exercise. The inflater must construct it via the (Context, AttributeSet)
 * constructor resolved against the PAYLOAD's DexClassLoader; the host's stock
 * LayoutInflater cannot see this class (same wall CoGo plugins hit before
 * PluginFragmentHelper.getPluginInflater).
 *
 * Deliberately NO payload-defined custom XML attrs: only android: attrs appear
 * in the layout, and all styling knobs are set from code via setColors(...).
 * Custom attrs from a dynamically-loaded resource table are a separate hazard
 * (attr ids live in the payload's 0x80 namespace; obtainStyledAttributes runs
 * against the host theme) — kept out of scope on purpose.
 */
public final class SparklineView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private int[] values = new int[0];
    /** 0..1 left-to-right reveal fraction driven by the ValueAnimator. */
    private float reveal = 1f;
    private ValueAnimator animator;

    public SparklineView(Context context) {
        this(context, null);
    }

    /** The constructor the XML inflater uses. */
    public SparklineView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SparklineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(dp(1f));
        setColors(0xFF2E7D6B, 0x2E2E7D6B, 0xFF1F5A4C);
    }

    /** Styling from code (see class doc: no custom attrs by design). */
    public void setColors(int line, int fill, int dot) {
        linePaint.setColor(line);
        fillPaint.setColor(fill);
        dotPaint.setColor(dot);
        basePaint.setColor((dot & 0x00FFFFFF) | 0x30000000);
        invalidate();
    }

    /** Sets the series (0..100 each) and optionally replays the reveal animation. */
    public void setValues(int[] scores, boolean animate) {
        this.values = scores.clone();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (animate && values.length >= 2) {
            reveal = 0f;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(700);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                reveal = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            reveal = 1f;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = dp(6f);
        float dotR = dp(3.5f);

        // Baseline along the bottom, always visible.
        canvas.drawLine(pad, h - pad, w - pad, h - pad, basePaint);
        if (values.length == 0 || w <= 2 * pad) {
            return;
        }
        if (values.length == 1) {
            float y = yFor(values[0], pad, h, 0, 100);
            canvas.drawCircle(w / 2f, y, dotR, dotPaint);
            return;
        }

        // Auto-scale the y range to the data (with headroom), clamped to 0..100.
        int lo = 100;
        int hi = 0;
        for (int v : values) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        lo = Math.max(0, lo - 8);
        hi = Math.min(100, hi + 8);
        if (lo == hi) {
            lo = Math.max(0, lo - 10);
            hi = Math.min(100, hi + 10);
        }

        float span = w - 2 * pad;
        float step = span / (values.length - 1);
        linePath.rewind();
        fillPath.rewind();
        for (int i = 0; i < values.length; i++) {
            float x = pad + i * step;
            float y = yFor(values[i], pad, h, lo, hi);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, h - pad);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(pad + span, h - pad);
        fillPath.close();

        // The animator reveals the chart left-to-right via a clip.
        canvas.save();
        canvas.clipRect(0, 0, pad + span * reveal + dotR, h);
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
        for (int i = 0; i < values.length; i++) {
            canvas.drawCircle(pad + i * step, yFor(values[i], pad, h, lo, hi), dotR, dotPaint);
        }
        canvas.restore();
    }

    private float yFor(int value, float pad, float h, int lo, int hi) {
        float frac = (value - lo) / (float) (hi - lo);
        return (h - pad) - frac * (h - 2 * pad);
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
