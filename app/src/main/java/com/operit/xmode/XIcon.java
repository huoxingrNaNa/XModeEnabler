package com.operit.xmode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 24x24 网格线框图标，全部 Canvas 绘制，不引入任何图片资源。
 */
public class XIcon extends View {

    public static final int CHECK = 0;
    public static final int ZAP = 1;
    public static final int SHIELD = 2;
    public static final int CLOCK = 3;
    public static final int SLIDERS = 4;
    public static final int BATTERY = 5;
    public static final int PLAY = 6;
    public static final int STOP = 7;
    public static final int INFO = 8;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int type;

    public XIcon(Context context, int type, int color) {
        super(context);
        this.type = type;
        float d = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.9f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    public void setIconColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    public void setIconType(int type) {
        this.type = type;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float u = Math.min(w, h) / 24f;
        canvas.save();
        canvas.translate((w - 24 * u) / 2f, (h - 24 * u) / 2f);

        switch (type) {
            case CHECK:
                path.reset();
                path.moveTo(4.5f * u, 12.5f * u);
                path.lineTo(9.5f * u, 17.5f * u);
                path.lineTo(19.5f * u, 7f * u);
                canvas.drawPath(path, stroke);
                break;
            case ZAP:
                path.reset();
                path.moveTo(13f * u, 2.5f * u);
                path.lineTo(6f * u, 13.5f * u);
                path.lineTo(11.5f * u, 13.5f * u);
                path.lineTo(10f * u, 21.5f * u);
                path.lineTo(18.5f * u, 10.5f * u);
                path.lineTo(12.5f * u, 10.5f * u);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case SHIELD:
                path.reset();
                path.moveTo(12f * u, 3f * u);
                path.lineTo(20.5f * u, 6.5f * u);
                path.lineTo(20.5f * u, 12f * u);
                path.quadTo(20.5f * u, 17.5f * u, 12f * u, 21f * u);
                path.quadTo(3.5f * u, 17.5f * u, 3.5f * u, 12f * u);
                path.lineTo(3.5f * u, 6.5f * u);
                path.close();
                canvas.drawPath(path, stroke);
                break;
            case CLOCK:
                canvas.drawCircle(12 * u, 12 * u, 8 * u, stroke);
                canvas.drawLine(12 * u, 12 * u, 12 * u, 7.5f * u, stroke);
                canvas.drawLine(12 * u, 12 * u, 15.5f * u, 13.5f * u, stroke);
                break;
            case SLIDERS:
                canvas.drawLine(4 * u, 7 * u, 20 * u, 7 * u, stroke);
                canvas.drawLine(4 * u, 12 * u, 20 * u, 12 * u, stroke);
                canvas.drawLine(4 * u, 17 * u, 20 * u, 17 * u, stroke);
                canvas.drawCircle(16 * u, 7 * u, 1.9f * u, fill);
                canvas.drawCircle(9 * u, 12 * u, 1.9f * u, fill);
                canvas.drawCircle(14 * u, 17 * u, 1.9f * u, fill);
                break;
            case BATTERY:
                rect.set(3 * u, 7 * u, 18f * u, 17f * u);
                canvas.drawRoundRect(rect, 2 * u, 2 * u, stroke);
                canvas.drawLine(20.5f * u, 10 * u, 20.5f * u, 14 * u, stroke);
                rect.set(6 * u, 10 * u, 12 * u, 14 * u);
                canvas.drawRoundRect(rect, 1 * u, 1 * u, fill);
                break;
            case PLAY:
                path.reset();
                path.moveTo(8 * u, 5 * u);
                path.lineTo(19 * u, 12 * u);
                path.lineTo(8 * u, 19 * u);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case STOP:
                rect.set(6.5f * u, 6.5f * u, 17.5f * u, 17.5f * u);
                canvas.drawRoundRect(rect, 2.5f * u, 2.5f * u, fill);
                break;
            case INFO:
                canvas.drawCircle(12 * u, 12 * u, 8 * u, stroke);
                canvas.drawLine(12 * u, 11 * u, 12 * u, 16.5f * u, stroke);
                canvas.drawCircle(12 * u, 8 * u, 1.2f * u, fill);
                break;
            default:
                break;
        }
        canvas.restore();
    }
}