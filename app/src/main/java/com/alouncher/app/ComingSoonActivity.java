package com.alouncher.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pantalla vacía provisional: título + "Próximamente". Se llenará en fases posteriores. */
public class ComingSoonActivity extends Activity {
    static final String EXTRA_TITLE = "title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FontManager.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(getColor(R.color.bg));
        int pad = BottomSheet.dp(this, 32);
        root.setPadding(pad, pad, pad, pad);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        TextView t = new TextView(this);
        t.setText(title != null ? title : "");
        t.setTextColor(getColor(R.color.ink));
        t.setTextSize(24);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        root.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(this);
        msg.setText(R.string.proximamente);
        msg.setTextColor(getColor(R.color.mut));
        msg.setTextSize(15);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = BottomSheet.dp(this, 10);
        root.addView(msg, mp);

        TextView back = BottomSheet.button(this, getString(R.string.volver), false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                BottomSheet.dp(this, 200), ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = BottomSheet.dp(this, 28);
        root.addView(back, bp);

        FontManager.apply(root);
        setContentView(root);
    }
}
