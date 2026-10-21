package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A small window with a roller shade, drawn with a few rects: the shade covers the glass from the top down to
 * (100 - position)% so 100 is fully open and 0 fully closed. Cheap enough to redraw on every remote key press.
 */
final class BlindView extends View {
    private final float d;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int position = 100;   // 0 closed .. 100 open
    private int target = -1;      // the value being chosen, drawn as a faint line

    BlindView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(3 * d);
        frame.setColor(0x80FFFFFF);
        shade.setColor(0xFFE9DCCB);
        slat.setColor(0x26000000);
        slat.setStrokeWidth(Math.max(1, d));
        bar.setColor(0xFFC9B39A);
    }

    void setPosition(int p) { position = clamp(p); invalidate(); }

    void setTarget(int p) { target = p < 0 ? -1 : clamp(p); invalidate(); }

    private static int clamp(int p) { return Math.max(0, Math.min(100, p)); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = 3 * d, radius = 10 * d;
        r.set(pad, pad, w - pad, h - pad);
        glass.setShader(new LinearGradient(0, 0, 0, h, 0xFF2B4A6B, 0xFF6E93B5, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, glass);
        // The shade, from the top down to where the blind is.
        float inner = 3 * d;
        float bottom = pad + inner + (h - 2 * pad - 2 * inner) * (100 - position) / 100f;
        c.save();
        c.clipRect(pad + inner, pad + inner, w - pad - inner, h - pad - inner);
        c.drawRect(pad + inner, pad + inner, w - pad - inner, bottom, shade);
        for (float y = pad + inner + 9 * d; y < bottom - 2 * d; y += 9 * d) c.drawLine(pad + inner, y, w - pad - inner, y, slat);
        c.drawRect(pad + inner, Math.max(pad + inner, bottom - 5 * d), w - pad - inner, bottom, bar);
        if (target >= 0 && target != position) {
            float ty = pad + inner + (h - 2 * pad - 2 * inner) * (100 - target) / 100f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFF7CAC9);
            p.setStrokeWidth(2.5f * d);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * d, 6 * d}, 0));
            c.drawLine(pad + inner, ty, w - pad - inner, ty, p);
        }
        c.restore();
        // Cross bar of the window, then the frame on top.
        frame.setStrokeWidth(2 * d);
        if (bottom < h - pad - inner) c.drawLine(w / 2, bottom, w / 2, h - pad, frame);
        frame.setStrokeWidth(3 * d);
        c.drawRoundRect(r, radius, radius, frame);
    }
}
