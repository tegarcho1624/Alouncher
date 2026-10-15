package com.alouncher.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Hoja inferior oscura con título, cuerpo desplazable y zona de botones. */
final class BottomSheet {
    private final Activity act;
    private final Dialog dialog;
    private final LinearLayout sheet;
    private final LinearLayout body;
    private final LinearLayout footer;
    private boolean keyboard;
    private final TextView titleView;

    BottomSheet(Activity act, String title, String subtitle) {
        this.act = act;
        dialog = new Dialog(act, R.style.SheetDialog);

        sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.sheet_bg);
        sheet.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 20));

        View grip = new View(act);
        grip.setBackgroundResource(R.drawable.grip_bg);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(act, 38), dp(act, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(act, 14);
        sheet.addView(grip, gp);

        TextView t = new TextView(act);
        titleView = t;
        t.setText(title);
        t.setTextColor(act.getColor(R.color.ink));
        t.setTextSize(22);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        sheet.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null) {
            TextView s = new TextView(act);
            s.setText(subtitle);
            s.setTextColor(act.getColor(R.color.mut));
            s.setTextSize(12);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(act, 4);
            sheet.addView(s, sp);
        }

        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        MaxHeightScrollView scroll = new MaxHeightScrollView(act, Math.round(screenH * 0.5f));
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(act, 14);
        sheet.addView(scroll, bp);

        footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(act, 16);
        sheet.addView(footer, fp);
    }

    TextView title() { return titleView; }

    /** Se llama cuando la hoja se cierra (por el botón, tocando fuera o con "atrás"). */
    void setOnDismissListener(Runnable r) { dialog.setOnDismissListener(d -> r.run()); }

    LinearLayout body() { return body; }

    LinearLayout footer() { return footer; }

    /** Recibe los eventos de arrastre que ocurren dentro de la hoja (coordenadas de la hoja). */
    void setOnDragListener(View.OnDragListener l) { sheet.setOnDragListener(l); }

    /** Abre el teclado al mostrar la hoja (para formularios). */
    void keyboard() { keyboard = true; }

    void show() {
        FontManager.apply(sheet);
        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            int sw = act.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(sw, dp(act, 560)), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.72f);
            w.setWindowAnimations(R.style.SheetAnim);
            if (keyboard) {
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    /** Botón de la hoja: "solid" = acción principal (papel), si no = secundario. */
    static TextView button(Context c, String text, boolean solid) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setTextSize(15);
        t.setClickable(true);
        t.setFocusable(true);
        if (solid) {
            t.setTextColor(c.getColor(R.color.pi));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setBackgroundResource(R.drawable.btn_solid);
        } else {
            t.setTextColor(c.getColor(R.color.ink));
            t.setBackgroundResource(R.drawable.btn_ghost);
        }
        return t;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }
}
