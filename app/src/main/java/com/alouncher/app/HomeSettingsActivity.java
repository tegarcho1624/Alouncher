package com.alouncher.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Configuración de pantalla principal: Estilo de Escritura (fuentes) y disposición (próximamente). */
public class HomeSettingsActivity extends Activity {

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FontManager.init(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // Encabezado: flecha de regreso + título
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackgroundResource(R.drawable.item_bg);
        back.setContentDescription(getString(R.string.atras));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText(R.string.config_home);
        title.setTextColor(getColor(R.color.ink));
        title.setTextSize(22);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(12);
        header.addView(title, tp);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(row(getString(R.string.estilo_escritura), getString(R.string.estilos),
                v -> startActivity(new Intent(this, FontsActivity.class))));
        root.addView(divider());
        root.addView(row(getString(R.string.disposicion),
                HomeStore.HOME_COLS + "\u00d7" + HomeStore.HOME_ROWS,
                v -> Toast.makeText(this, R.string.proximamente, Toast.LENGTH_SHORT).show()));
        root.addView(divider());

        setContentView(root);
        FontManager.apply(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FontManager.apply(root); // por si se cambió la fuente en la pantalla de Estilos
    }

    private View row(String label, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(28), 0, dp(24), 0);
        row.setBackgroundResource(R.drawable.item_bg);
        row.setOnClickListener(click);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.ink));
        l.setTextSize(17);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(getColor(R.color.mut));
        v.setTextSize(15);
        row.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(20), dp(20));
        cp.leftMargin = dp(8);
        row.addView(chevron, cp);
        return row;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(getColor(R.color.ln));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(28), 0, dp(24), 0);
        d.setLayoutParams(lp);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
