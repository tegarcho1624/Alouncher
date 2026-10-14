package com.alouncher.app;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Piezas de interfaz compartidas por las pantallas de fondos (selector segmentado, etc.). */
final class UiKit {
    private UiKit() {}

    interface OnPick {
        void pick(int index);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Selector segmentado: botones iguales, el activo en dorado (borde dorado + texto claro + relleno dorado tenue). */
    static LinearLayout segmented(Context c, String[] labels, int selected, OnPick listener) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 44));
            t.setTextSize(15);
            t.setClickable(true);
            t.setFocusable(true);
            final int index = i;
            t.setOnClickListener(v -> {
                select(box, index);
                listener.pick(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(c, 8);
            box.addView(t, lp);
        }
        select(box, selected);
        return box;
    }

    /** Botón alternable: encendido = dorado (borde dorado + texto claro + relleno tenue); apagado = apagado. */
    static void styleToggle(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.seg_on : R.drawable.seg_off);
        t.setTextColor(t.getContext().getColor(on ? R.color.gold_hi : R.color.mut));
        t.setSelected(on);
    }

    static void select(LinearLayout box, int selected) {
        for (int i = 0; i < box.getChildCount(); i++) {
            TextView t = (TextView) box.getChildAt(i);
            boolean on = i == selected;
            t.setBackgroundResource(on ? R.drawable.seg_on : R.drawable.seg_off);
            t.setTextColor(box.getContext().getColor(on ? R.color.gold_hi : R.color.mut));
        }
    }
}
