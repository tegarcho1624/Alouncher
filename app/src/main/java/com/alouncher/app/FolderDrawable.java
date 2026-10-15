package com.alouncher.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.List;

/** Carpeta cerrada: un cuadrado translúcido (del tamaño de un ícono) con las miniaturas de las primeras apps en un grid 2×2. */
final class FolderDrawable extends Drawable {
    private final List<Drawable> icons;
    private final int size;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    FolderDrawable(List<Drawable> icons, int sizePx) {
        this.icons = icons;
        this.size = sizePx;
        bg.setColor(0x66161310);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0x40F1E9D8);
        line.setStrokeWidth(Math.max(1f, sizePx / 64f));
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = Math.min(b.width(), b.height());
        rect.set(b.left, b.top, b.left + w, b.top + w);
        float radius = w * 0.26f;
        canvas.drawRoundRect(rect, radius, radius, bg);
        canvas.drawRoundRect(rect, radius, radius, line);

        float pad = w * 0.13f;
        float gap = w * 0.07f;
        float cell = (w - 2f * pad - gap) / 2f;
        int n = Math.min(4, icons.size());
        for (int i = 0; i < n; i++) {
            float left = b.left + pad + (i % 2) * (cell + gap);
            float top = b.top + pad + (i / 2) * (cell + gap);
            Drawable d = icons.get(i);
            d.setBounds(Math.round(left), Math.round(top), Math.round(left + cell), Math.round(top + cell));
            d.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(ColorFilter colorFilter) { }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
