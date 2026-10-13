package com.alouncher.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Barra horizontal dividida en segmentos (uno por página). Segmento activo en dorado; la principal lleva una casita. */
public class PageIndicator extends View {

    interface OnSelect {
        void onSelect(int index);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final Path clip = new Path();
    private final Path house = new Path();
    private final float density;
    private final float segW;
    private final float barH;

    private int count = 1;
    private int current = 0;
    private int main = 0;
    private OnSelect onSelect;

    public PageIndicator(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        segW = 40f * density;
        barH = 20f * density;
        fill.setColor(0xB3161310);
        active.setColor(0xFFC9A24B);
        sep.setColor(0x40F1E9D8);
        sep.setStrokeWidth(density);
        border.setColor(0x33F1E9D8);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(density);
        glyph.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    void setOnSelect(OnSelect l) {
        onSelect = l;
    }

    void setPages(int count, int main, int current) {
        boolean relayout = Math.max(1, count) != this.count;
        this.count = Math.max(1, count);
        this.main = main;
        this.current = current;
        if (relayout) requestLayout();
        invalidate();
    }

    void setCurrent(int i) {
        if (i != current) {
            current = i;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(count * segW) + 2, Math.round(36f * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = 1f;
        float top = (getHeight() - barH) / 2f;
        bar.set(left, top, left + count * segW, top + barH);
        float r = barH / 2f;

        canvas.drawRoundRect(bar, r, r, fill);

        // segmento activo, recortado a la forma redondeada de la barra
        canvas.save();
        clip.reset();
        clip.addRoundRect(bar, r, r, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRect(left + current * segW, bar.top, left + (current + 1) * segW, bar.bottom, active);
        canvas.restore();

        // separadores entre segmentos
        for (int i = 1; i < count; i++) {
            float x = left + i * segW;
            canvas.drawLine(x, bar.top + 4f * density, x, bar.bottom - 4f * density, sep);
        }
        canvas.drawRoundRect(bar, r, r, border);

        // casita en el segmento de la página principal
        if (main >= 0 && main < count) {
            glyph.setColor(main == current ? 0xFF2A2218 : 0xFFF1E9D8);
            drawHouse(canvas, left + main * segW + segW / 2f, bar.centerY(), 11f * density);
        }
    }

    private void drawHouse(Canvas canvas, float cx, float cy, float s) {
        house.reset();
        house.moveTo(cx, cy - s * 0.55f);
        house.lineTo(cx + s * 0.6f, cy - s * 0.02f);
        house.lineTo(cx + s * 0.38f, cy - s * 0.02f);
        house.lineTo(cx + s * 0.38f, cy + s * 0.5f);
        house.lineTo(cx - s * 0.38f, cy + s * 0.5f);
        house.lineTo(cx - s * 0.38f, cy - s * 0.02f);
        house.lineTo(cx - s * 0.6f, cy - s * 0.02f);
        house.close();
        canvas.drawPath(house, glyph);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && onSelect != null) {
            int i = (int) ((e.getX() - 1f) / segW);
            if (i >= 0 && i < count) onSelect.onSelect(i);
        }
        return true;
    }
}
