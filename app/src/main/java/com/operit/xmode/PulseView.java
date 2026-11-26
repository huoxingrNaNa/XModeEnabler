package com.operit.xmode;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * X 模式状态指示灯：中心是 X 标识，生效时向外扩散双圈脉冲，待机时静止。
 * 纯绘制控件，不承载逻辑。
 */
public class PulseView extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private float phase;
    private boolean active;
    private int accent = 0xFF38E1C6;
    private ValueAnimator animator;

    public PulseView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.6f * density);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.6f * density);

        xPaint.setStyle(Paint.Style.STROKE);
        xPaint.setStrokeWidth(5.5f * density);
        xPaint.setStrokeCap(Paint.Cap.ROUND);

        applyAccent();
    }

    public void setAccent(int color) {
        if (accent == color) {
            return;
        }
        accent = color;
        applyAccent();
        invalidate();
    }

    private void applyAccent() {
        ringPaint.setColor(accent);
        edgePaint.setColor(withAlpha(accent, 0x66));
        xPaint.setColor(accent);
        corePaint.setStyle(Paint.Style.FILL);
    }

    public void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        if (active) {
            startPulse();
        } else {
            stopPulse();
        }
        invalidate();
    }

    private void startPulse() {
        stopPulse();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void stopPulse() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        phase = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float min = Math.min(w, h);
        float base = min * 0.28f;
        float maxR = min / 2f - 1f * density;

        if (active) {
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * 0.5f) % 1f;
                float radius = base + p * (maxR - base);
                ringPaint.setAlpha((int) (90 * (1f - p)));
                canvas.drawCircle(cx, cy, radius, ringPaint);
            }
        }

        corePaint.setShader(new RadialGradient(cx, cy, base,
                withAlpha(accent, 0x3A), withAlpha(accent, 0x08), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, base, corePaint);
        corePaint.setShader(null);

        canvas.drawCircle(cx, cy, base, edgePaint);

        float s = base * 0.5f;
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, xPaint);
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, xPaint);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}