package com.alouncher.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Fondos y temas. Un selector arriba (Home | App Drawer) y, debajo, tres filas con botón "ELEGIR ›":
 * Fondo, Fondo animado y Audio (el audio solo existe en la Home).
 */
public class BackgroundsActivity extends Activity {

    private LinearLayout root;
    private LinearLayout rows;
    private String scope = BackgroundStore.HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FontManager.init(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // Encabezado
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackgroundResource(R.drawable.item_bg);
        back.setContentDescription(getString(R.string.atras));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText(R.string.fondos_temas);
        title.setTextColor(getColor(R.color.ink));
        title.setTextSize(22);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(12);
        header.addView(title, tp);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Selector de destino: Home | App Drawer
        LinearLayout seg = UiKit.segmented(this,
                new String[]{getString(R.string.seg_home), getString(R.string.seg_drawer)}, 0, i -> {
                    scope = i == 0 ? BackgroundStore.HOME : BackgroundStore.DRAWER;
                    render();
                });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(24), dp(8), dp(24), dp(8));
        root.addView(seg, sp);

        // Filas
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(24), dp(8), dp(24), dp(24));
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // refresca los subtítulos al volver de un selector
    }

    private void render() {
        rows.removeAllViews();

        BackgroundStore.Playlist img = BackgroundStore.load(this, scope, BackgroundStore.IMAGE);
        BackgroundStore.Item cur = img.current();
        String imgSub = cur != null ? cur.name
                : getString(R.string.sin_fondo);
        if (!img.items.isEmpty()) {
            imgSub += " · " + getResources().getQuantityString(R.plurals.imagenes,
                    img.items.size(), img.items.size());
        }

        rows.addView(row(getString(R.string.fila_fondo), imgSub, true, v -> {
            Intent i = new Intent(this, PlaylistActivity.class);
            i.putExtra(PlaylistActivity.EXTRA_SCOPE, scope);
            i.putExtra(PlaylistActivity.EXTRA_TYPE, BackgroundStore.IMAGE);
            startActivity(i);
        }));

        BackgroundStore.Playlist vid = BackgroundStore.load(this, scope, BackgroundStore.VIDEO);
        BackgroundStore.Item vcur = vid.current();
        String vidSub = vcur != null ? vcur.name : getString(R.string.sin_animado_corto);
        if (!vid.items.isEmpty()) {
            vidSub += " · " + getResources().getQuantityString(R.plurals.videos,
                    vid.items.size(), vid.items.size());
        }
        rows.addView(row(getString(R.string.fila_fondo_animado), vidSub, true, v -> {
            Intent i = new Intent(this, PlaylistActivity.class);
            i.putExtra(PlaylistActivity.EXTRA_SCOPE, scope);
            i.putExtra(PlaylistActivity.EXTRA_TYPE, BackgroundStore.VIDEO);
            startActivity(i);
        }));

        // Siguiente sub-fase: se muestra ya, pero todavía no abre nada

        // El audio solo existe en la Home: en App Drawer la fila no aparece
        if (BackgroundStore.HOME.equals(scope)) {
            BackgroundStore.Playlist au = BackgroundStore.load(this, BackgroundStore.HOME, BackgroundStore.AUDIO);
            BackgroundStore.Item acur = au.current();
            String auSub = acur != null ? acur.name : getString(R.string.sin_audio);
            if (!au.items.isEmpty()) {
                auSub += " · " + getResources().getQuantityString(R.plurals.audios,
                        au.items.size(), au.items.size());
            }
            if (acur != null && !au.off && BackgroundStore.homeVideoSounds(this)) {
                auSub += getString(R.string.audio_pausa_video_corto); // el fondo animado suena
            }
            rows.addView(row(getString(R.string.fila_audio), auSub, true, v -> {
                Intent i = new Intent(this, PlaylistActivity.class);
                i.putExtra(PlaylistActivity.EXTRA_SCOPE, BackgroundStore.HOME);
                i.putExtra(PlaylistActivity.EXTRA_TYPE, BackgroundStore.AUDIO);
                startActivity(i);
            }));
        }
        FontManager.apply(root);
    }

    private View row(String title, String subtitle, boolean enabled, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_row_bg);
        card.setPadding(dp(20), dp(14), dp(14), dp(14));
        card.setAlpha(enabled ? 1f : 0.55f);
        card.setClickable(true);
        card.setOnClickListener(click);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = dp(12);
        card.setLayoutParams(cp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.ink));
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(getColor(R.color.mut));
        s.setTextSize(13);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(2);
        texts.addView(s, sp);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Botón "ELEGIR ›"
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setMinimumHeight(dp(44));
        btn.setPadding(dp(16), 0, dp(10), 0);
        btn.setBackgroundResource(R.drawable.btn_ghost);
        btn.setClickable(true);
        btn.setOnClickListener(click);
        TextView label = new TextView(this);
        label.setText(R.string.elegir);
        label.setTextColor(getColor(enabled ? R.color.gold_hi : R.color.mut));
        label.setTextSize(13);
        label.setLetterSpacing(0.08f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.addView(label);
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        btn.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        card.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private int dp(int v) {
        return UiKit.dp(this, v);
    }
}
