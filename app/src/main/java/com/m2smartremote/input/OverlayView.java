package com.m2smartremote.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** Draws the virtual-focus highlight and the touchpad cursor. All calls on the main thread. */
final class OverlayView extends View {
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final RectF tmp = new RectF();
    private final int[] location = new int[2];
    private final float density;

    private Rect focus;
    private boolean cursorVisible;
    private float cursorX;
    private float cursorY;

    OverlayView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(3 * density);
        focusPaint.setColor(0xFF5EC8FF);
        focusGlow.setStyle(Paint.Style.STROKE);
        focusGlow.setStrokeWidth(8 * density);
        focusGlow.setColor(0x405EC8FF);
        cursorFill.setColor(0xFFFFFFFF);
        cursorStroke.setStyle(Paint.Style.STROKE);
        cursorStroke.setStrokeWidth(1.5f * density);
        cursorStroke.setColor(0xFF111111);

        float s = density;
        cursorPath.moveTo(0, 0);
        cursorPath.lineTo(0, 22 * s);
        cursorPath.lineTo(6 * s, 16.5f * s);
        cursorPath.lineTo(10 * s, 25 * s);
        cursorPath.lineTo(13.5f * s, 23.5f * s);
        cursorPath.lineTo(9.5f * s, 15 * s);
        cursorPath.lineTo(17 * s, 15 * s);
        cursorPath.close();
    }

    void setFocus(Rect screenRect) {
        focus = screenRect == null ? null : new Rect(screenRect);
        invalidate();
    }

    void setCursor(boolean visible, float x, float y) {
        cursorVisible = visible;
        cursorX = x;
        cursorY = y;
        invalidate();
    }

    boolean isShowingAnything() {
        return focus != null || cursorVisible;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        getLocationOnScreen(location);
        canvas.save();
        canvas.translate(-location[0], -location[1]);
        if (focus != null) {
            float r = 10 * density;
            tmp.set(focus);
            tmp.inset(-2 * density, -2 * density);
            canvas.drawRoundRect(tmp, r, r, focusGlow);
            canvas.drawRoundRect(tmp, r, r, focusPaint);
        }
        if (cursorVisible) {
            canvas.save();
            canvas.translate(cursorX, cursorY);
            canvas.drawPath(cursorPath, cursorFill);
            canvas.drawPath(cursorPath, cursorStroke);
            canvas.restore();
        }
        canvas.restore();
    }
}
