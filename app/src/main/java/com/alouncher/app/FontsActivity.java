package com.alouncher.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selector de fuentes: "+" para importar (arriba a la izquierda), lupa para buscar (arriba a la derecha)
 * y una lista donde cada fuente se ve en su propia tipografía. La activa lleva borde dorado y palomita.
 */
public class FontsActivity extends Activity {

    private static final int REQ_IMPORT = 2001;
    private static final String[] FONT_MIME_TYPES = {
            "font/ttf", "font/otf", "font/sfnt", "font/truetype", "font/opentype",
            "application/x-font-ttf", "application/x-font-otf", "application/x-font-opentype",
            "application/x-font-truetype", "application/font-sfnt", "application/vnd.ms-opentype",
            // muchos .ttf/.otf llegan con este tipo genérico; al importar se comprueba que sea una fuente
            "application/octet-stream"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<FontManager.Option> all = new ArrayList<>();
    private final List<FontManager.Option> shown = new ArrayList<>();

    private LinearLayout header;
    private TextView title;
    private EditText search;
    private TextView empty;
    private RecyclerView list;
    private FontAdapter adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FontManager.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // ---- Encabezado: "+" | título o buscador | lupa
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));

        ImageView plus = iconButton(R.drawable.ic_plus, getString(R.string.importar_fuente));
        plus.setOnClickListener(v -> pickFont());
        header.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));

        FrameLayout mid = new FrameLayout(this);
        title = new TextView(this);
        title.setText(R.string.estilos);
        title.setTextColor(getColor(R.color.ink));
        title.setTextSize(20);
        mid.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        search = new EditText(this);
        search.setHint(R.string.buscar_fuente);
        search.setHintTextColor(getColor(R.color.mut));
        search.setTextColor(getColor(R.color.ink));
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackgroundResource(R.drawable.input_bg);
        search.setMinHeight(dp(44));
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString();
                filter();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        sp.setMargins(dp(8), 0, dp(8), 0);
        mid.addView(search, sp);
        header.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView lupa = iconButton(R.drawable.ic_search, getString(R.string.buscar));
        lupa.setOnClickListener(v -> toggleSearch());
        header.addView(lupa, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Lista
        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(dp(20), dp(8), dp(20), dp(24));
        adapter = new FontAdapter();
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText(R.string.sin_resultados);
        empty.setTextColor(getColor(R.color.mut));
        empty.setTextSize(15);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        FontManager.apply(header); // las filas NO: cada una se muestra en su propia tipografía
        FontManager.apply(empty);
        refresh();
    }

    private ImageView iconButton(int res, String description) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setColorFilter(getColor(R.color.gold_hi)); // acento de Alouncher
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.item_bg);
        b.setContentDescription(description);
        return b;
    }

    // ---- Lista ---------------------------------------------------------------------

    private void refresh() {
        all.clear();
        all.addAll(FontManager.options(this));
        filter();
    }

    private void filter() {
        shown.clear();
        String q = query.trim().toLowerCase(Locale.getDefault());
        for (FontManager.Option o : all) {
            if (q.isEmpty() || o.name.toLowerCase(Locale.getDefault()).contains(q)) shown.add(o);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void choose(FontManager.Option o) {
        if (!FontManager.select(this, o)) {
            Toast.makeText(this, R.string.fuente_no_valida, Toast.LENGTH_SHORT).show();
            return;
        }
        adapter.notifyDataSetChanged();
        FontManager.apply(header);
        FontManager.apply(empty);
    }

    private static final class FontVH extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final TextView name;
        final ImageView check;

        FontVH(LinearLayout row, TextView name, ImageView check) {
            super(row);
            this.row = row;
            this.name = name;
            this.check = check;
        }
    }

    private final class FontAdapter extends RecyclerView.Adapter<FontVH> {

        @Override
        public FontVH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(FontsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(72));
            row.setPadding(dp(22), dp(12), dp(18), dp(12));
            row.setBackgroundResource(R.drawable.font_row_bg);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(5), 0, dp(5)); // espacio para que cada vista previa respire
            row.setLayoutParams(lp);

            TextView name = new TextView(FontsActivity.this);
            name.setTextColor(getColor(R.color.ink));
            name.setTextSize(24);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView check = new ImageView(FontsActivity.this);
            check.setImageResource(R.drawable.ic_check);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(24), dp(24));
            cp.leftMargin = dp(12);
            row.addView(check, cp);
            return new FontVH(row, name, check);
        }

        @Override
        public void onBindViewHolder(FontVH h, int position) {
            FontManager.Option o = shown.get(position);
            boolean current = o.id.equals(FontManager.activeId());
            h.name.setText(o.name);                              // nombre sin extensión
            h.name.setTypeface(FontManager.preview(FontsActivity.this, o)); // en su propia tipografía
            h.row.setSelected(current);
            h.check.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            h.row.setOnClickListener(v -> choose(o));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    // ---- Buscar -------------------------------------------------------------------------

    private void toggleSearch() {
        boolean open = search.getVisibility() == View.VISIBLE;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (open) {
            search.setText("");
            search.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            query = "";
            filter();
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } else {
            title.setVisibility(View.GONE);
            search.setVisibility(View.VISIBLE);
            FontManager.apply(search);
            search.requestFocus();
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void onBackPressed() {
        if (search.getVisibility() == View.VISIBLE) {
            toggleSearch();
        } else {
            super.onBackPressed();
        }
    }

    // ---- Importar --------------------------------------------------------------------------

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, FONT_MIME_TYPES);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_importar, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return; // cancelado
        final Uri uri = data.getData();
        final Context app = getApplicationContext();
        new Thread(() -> {
            FontManager.ImportResult r = FontManager.importFont(app, uri);
            ui.post(() -> {
                if (isDestroyed()) return;
                onImported(r);
            });
        }).start();
    }

    private void onImported(FontManager.ImportResult r) {
        switch (r.error) {
            case FontManager.ERR_NONE:
                refresh();
                FontManager.apply(header); // por si la fuente reemplazada era la activa
                FontManager.apply(empty);
                Toast.makeText(this, getString(r.replaced ? R.string.fuente_actualizada
                        : R.string.fuente_importada, r.name), Toast.LENGTH_SHORT).show();
                for (int i = 0; i < shown.size(); i++) {
                    if (shown.get(i).name.equalsIgnoreCase(r.name)) {
                        list.scrollToPosition(i);
                        break;
                    }
                }
                break;
            case FontManager.ERR_EXT:
                Toast.makeText(this, R.string.elegir_ttf, Toast.LENGTH_LONG).show();
                break;
            case FontManager.ERR_INVALID:
                Toast.makeText(this, R.string.fuente_no_valida, Toast.LENGTH_LONG).show();
                break;
            default:
                Toast.makeText(this, R.string.error_importar, Toast.LENGTH_LONG).show();
                break;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
