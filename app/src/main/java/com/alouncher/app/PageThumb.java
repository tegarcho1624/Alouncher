package com.alouncher.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Miniatura de una página: dibuja sus íconos en la misma cuadrícula que la Home. */
public class PageThumb extends View {
    private final List<Drawable> icons = new ArrayList<>();
    private final List<int[]> cells = new ArrayList<>();
    private final float density;

    public PageThumb(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    void setItems(List<Drawable> drawables, List<int[]> rowCols) {
        icons.clear();
        icons.addAll(drawables);
        cells.clear();
        cells.addAll(rowCols);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (icons.isEmpty()) return;
        float pad = 24f * density;
        float cw = (getWidth() - 2f * pad) / HomeStore.HOME_COLS;
        float ch = (getHeight() - 2f * pad) / HomeStore.HOME_ROWS;
        float size = Math.min(cw, ch) * 0.62f;
        for (int i = 0; i < icons.size(); i++) {
            int[] rc = cells.get(i);
            float cx = pad + (rc[1] + 0.5f) * cw;
            float cy = pad + (rc[0] + 0.5f) * ch;
            Drawable d = icons.get(i);
            d.setBounds(Math.round(cx - size / 2f), Math.round(cy - size / 2f),
                    Math.round(cx + size / 2f), Math.round(cy + size / 2f));
            d.draw(canvas);
        }
    }
}
