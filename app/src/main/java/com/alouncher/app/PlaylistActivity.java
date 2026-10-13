package com.alouncher.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Selector de recursos de una lista (por ahora: imágenes de fondo).
 * Cada fila: miniatura · nombre y extensión · palomita si es la activa · eliminar · asa para arrastrar.
 * Tocar la fila aplica ese fondo. Eliminar pide confirmación (Sí / No). Arrastrar reordena.
 */
public class PlaylistActivity extends Activity {
    static final String EXTRA_SCOPE = "scope";
    static final String EXTRA_TYPE = "type";

    private static final int REQ_ADD = 3001;
    private static final String[] MODES = {
            BackgroundStore.MODE_FILL, BackgroundStore.MODE_STRETCH, BackgroundStore.MODE_CENTER};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String scope;
    private String type;
    private BackgroundStore.Playlist pl;

    private LinearLayout root;
    private TextView subtitle;
    private TextView emptyView;
    private RecyclerView list;
    private RowAdapter adapter;
    private ItemTouchHelper touch;

    private static final class RowVH extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final ImageView thumb;
        final TextView name;
        final TextView meta;
        final ImageView check;
        final ImageView trash;
        final ImageView grip;

        RowVH(LinearLayout row, ImageView thumb, TextView name, TextView meta,
              ImageView check, ImageView trash, ImageView grip) {
            super(row);
            this.row = row;
            this.thumb = thumb;
            this.name = name;
            this.meta = meta;
            this.check = check;
            this.trash = trash;
            this.grip = grip;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FontManager.init(this);
        String s = getIntent().getStringExtra(EXTRA_SCOPE);
        String t = getIntent().getStringExtra(EXTRA_TYPE);
        scope = BackgroundStore.DRAWER.equals(s) ? BackgroundStore.DRAWER : BackgroundStore.HOME;
        type = t != null ? t : BackgroundStore.IMAGE;
        pl = BackgroundStore.load(this, scope, type);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // ---- Encabezado: atrás + título + "Aplicado: …"
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

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(getString(R.string.fondo_de, getString(
                BackgroundStore.HOME.equals(scope) ? R.string.seg_home : R.string.seg_drawer)));
        title.setTextColor(getColor(R.color.ink));
        title.setTextSize(20);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titles.addView(title);
        subtitle = new TextView(this);
        subtitle.setTextColor(getColor(R.color.mut));
        subtitle.setTextSize(13);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(subtitle);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        header.addView(titles, tp);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Modo de ajuste: Rellenar · Estirar · Centrada
        LinearLayout seg = UiKit.segmented(this, new String[]{
                getString(R.string.modo_rellenar), getString(R.string.modo_estirar),
                getString(R.string.modo_centrada)}, modeIndex(pl.mode), i -> {
                    pl.mode = MODES[i];
                    BackgroundStore.save(this, scope, type, pl);
                });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(24), dp(4), dp(24), dp(8));
        root.addView(seg, sp);

        // ---- Lista
        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(dp(20), dp(4), dp(20), dp(12));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setText(R.string.vacio_imagenes);
        emptyView.setTextColor(getColor(R.color.mut));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(40), 0, dp(40), 0);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Pie: Quitar fondo (destructiva, secundaria) + Añadir (acción principal)
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(24), dp(8), dp(24), dp(20));
        TextView remove = BottomSheet.button(this, getString(R.string.quitar_fondo), false);
        remove.setTextColor(getColor(R.color.danger));
        remove.setOnClickListener(v -> clearActive());
        footer.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView add = BottomSheet.button(this, getString(R.string.anadir_imagenes), true);
        add.setOnClickListener(v -> pickImages());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(10);
        footer.addView(add, ap);
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Orden por arrastre (asa o pulsación larga)
        touch = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition();
                int b = to.getBindingAdapterPosition();
                if (a < 0 || b < 0) return false;
                BackgroundStore.Item moved = pl.items.remove(a);
                pl.items.add(b, moved);
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) { }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
                super.onSelectedChanged(vh, state);
                if (state == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    vh.itemView.setAlpha(0.9f);
                }
            }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setAlpha(1f);
                BackgroundStore.save(PlaylistActivity.this, scope, type, pl); // el orden se guarda al soltar
            }
        });
        touch.attachToRecyclerView(list);

        setContentView(root);
        FontManager.apply(root);
        refreshHeader();
    }

    private int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    private void refreshHeader() {
        BackgroundStore.Item cur = pl.current();
        if (cur != null) {
            subtitle.setText(getString(R.string.aplicado, cur.name));
        } else {
            subtitle.setText(BackgroundStore.HOME.equals(scope)
                    ? R.string.sin_fondo_sistema : R.string.sin_fondo_drawer);
        }
        emptyView.setVisibility(pl.items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---- Lista -------------------------------------------------------------------

    private final class RowAdapter extends RecyclerView.Adapter<RowVH> {
        @Override
        public RowVH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(PlaylistActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(72));
            row.setPadding(dp(10), dp(8), dp(4), dp(8));
            row.setBackgroundResource(R.drawable.list_row_bg);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(lp);

            ImageView thumb = new ImageView(PlaylistActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(getColor(R.color.sf3));
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
            thumb.setClipToOutline(true);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout col = new LinearLayout(PlaylistActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(PlaylistActivity.this);
            name.setTextSize(15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            TextView meta = new TextView(PlaylistActivity.this);
            meta.setTextSize(11);
            meta.setLetterSpacing(0.08f);
            meta.setTextColor(getColor(R.color.mut));
            col.addView(meta);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.leftMargin = dp(12);
            row.addView(col, cp);

            ImageView check = new ImageView(PlaylistActivity.this);
            check.setImageResource(R.drawable.ic_check);
            check.setContentDescription(getString(R.string.aplicado, ""));
            row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));

            ImageView trash = new ImageView(PlaylistActivity.this);
            trash.setImageResource(R.drawable.ic_trash);
            trash.setScaleType(ImageView.ScaleType.CENTER);
            trash.setBackgroundResource(R.drawable.item_bg);
            trash.setContentDescription(getString(R.string.eliminar_recurso));
            row.addView(trash, new LinearLayout.LayoutParams(dp(44), dp(44))); // a la izquierda del asa

            ImageView grip = new ImageView(PlaylistActivity.this);
            grip.setImageResource(R.drawable.ic_grip);
            grip.setScaleType(ImageView.ScaleType.CENTER);
            grip.setContentDescription(getString(R.string.arrastrar_ordenar));
            row.addView(grip, new LinearLayout.LayoutParams(dp(44), dp(44)));

            return new RowVH(row, thumb, name, meta, check, trash, grip);
        }

        @Override
        public void onBindViewHolder(RowVH h, int position) {
            BackgroundStore.Item it = pl.items.get(position);
            boolean current = it.id.equals(pl.cur);
            h.row.setSelected(current);
            h.name.setText(it.name);
            h.name.setTextColor(getColor(current ? R.color.gold_hi : R.color.ink));
            h.meta.setText(it.ext());
            h.check.setVisibility(current ? View.VISIBLE : View.GONE);
            ImageUtil.loadThumb(BackgroundStore.fileOf(PlaylistActivity.this, type, it), h.thumb, dp(52));

            h.row.setOnClickListener(v -> applyItem(it));
            h.trash.setOnClickListener(v -> confirmDelete(it));
            h.grip.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touch.startDrag(h);
                return false;
            });
            FontManager.apply(h.row);
        }

        @Override
        public int getItemCount() {
            return pl.items.size();
        }
    }

    // ---- Acciones --------------------------------------------------------------------

    private void applyItem(BackgroundStore.Item it) {
        if (it.id.equals(pl.cur)) return;
        pl.cur = it.id;
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshHeader();
        Toast.makeText(this, R.string.fondo_aplicado, Toast.LENGTH_SHORT).show();
    }

    private void clearActive() {
        if (pl.cur.isEmpty()) return;
        pl.cur = "";
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshHeader();
        Toast.makeText(this, R.string.fondo_quitado, Toast.LENGTH_SHORT).show();
    }

    /** Menú de confirmación al eliminar: "Sí" (rojo) y "No". */
    private void confirmDelete(BackgroundStore.Item it) {
        BottomSheet s = new BottomSheet(this, getString(R.string.confirmar_eliminar),
                getString(R.string.confirmar_eliminar_detalle, it.name));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView yes = BottomSheet.button(this, getString(R.string.si), false);
        yes.setTextColor(getColor(R.color.danger));
        TextView no = BottomSheet.button(this, getString(R.string.no), true);
        yes.setOnClickListener(v -> {
            s.dismiss();
            deleteItem(it);
        });
        no.setOnClickListener(v -> s.dismiss());
        buttons.addView(yes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        np.leftMargin = dp(10);
        buttons.addView(no, np);
        s.footer().addView(buttons);
        s.show();
    }

    private void deleteItem(BackgroundStore.Item it) {
        int idx = pl.indexOf(it.id);
        if (idx < 0) return;
        pl.items.remove(idx);
        if (it.id.equals(pl.cur)) pl.cur = ""; // era el activo: vuelve el fondo por defecto
        BackgroundStore.deleteFile(this, type, it);
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshHeader();
    }

    // ---- Añadir desde el dispositivo ----------------------------------------------------

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_ADD);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_importar_archivo, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ADD) return;
        if (resultCode != RESULT_OK || data == null) return; // cancelado: no cambia nada

        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        final Context app = getApplicationContext();
        new Thread(() -> {
            final List<BackgroundStore.Item> added = new ArrayList<>();
            int failed = 0;
            for (Uri u : uris) {
                BackgroundStore.Item it = BackgroundStore.importFile(app, type, u);
                if (it != null) added.add(it); else failed++;
            }
            final int failedCount = failed;
            ui.post(() -> {
                if (isDestroyed()) return;
                boolean wasEmpty = pl.items.isEmpty();
                pl.items.addAll(added);
                if (wasEmpty && !added.isEmpty()) pl.cur = added.get(0).id; // la primera pasa a ser el fondo
                if (!added.isEmpty()) BackgroundStore.save(this, scope, type, pl);
                adapter.notifyDataSetChanged();
                refreshHeader();
                if (!added.isEmpty()) {
                    Toast.makeText(this, getResources().getQuantityString(R.plurals.imagenes_anadidas,
                            added.size(), added.size()), Toast.LENGTH_SHORT).show();
                    list.scrollToPosition(pl.items.size() - 1);
                }
                if (failedCount > 0) {
                    Toast.makeText(this, R.string.imagen_no_leida, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private int dp(int v) {
        return UiKit.dp(this, v);
    }
}
