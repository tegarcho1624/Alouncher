package com.alouncher.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Selector de recursos de una lista: imágenes de fondo, videos de fondo animado o audios (solo Home).
 * Cada fila: miniatura/carátula · nombre y extensión · palomita (o ▶/⏸ en audio) · eliminar · asa para arrastrar.
 * Tocar la fila aplica ese recurso. Eliminar pide confirmación (Sí / No). Arrastrar reordena.
 * Videos y audios: anterior/siguiente, Aleatorio, Repetir (la lista) y Bucle (el recurso actual).
 * Videos: además Silenciar/Activar sonido y volumen. Audio: volumen.
 *
 * Nota: este selector NO es la Home, así que aquí el audio no suena (solo suena en la Home). El botón ▶/⏸
 * activa o pausa a mano el audio; se oye al volver a la Home.
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
    private boolean isVideo;
    private boolean isAudio;
    private String navKey;
    private BackgroundStore.Playlist pl;

    private LinearLayout root;
    private TextView subtitle;
    private TextView emptyView;
    private TextView nowPlaying;
    private ImageView prevBtn;
    private ImageView nextBtn;
    private TextView tShuffle;
    private TextView tRepeat;
    private TextView tLoop;
    private TextView tSound;
    private SeekBar seek;
    private TextView volLabel;
    private TextView hintExtra;
    private RecyclerView list;
    private RowAdapter adapter;
    private ItemTouchHelper touch;

    private static final class RowVH extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final ImageView thumb;
        final TextView name;
        final TextView meta;
        final ImageView check;
        final ImageView play;
        final ImageView trash;
        final ImageView grip;

        RowVH(LinearLayout row, ImageView thumb, TextView name, TextView meta,
              ImageView check, ImageView play, ImageView trash, ImageView grip) {
            super(row);
            this.row = row;
            this.thumb = thumb;
            this.name = name;
            this.meta = meta;
            this.check = check;
            this.play = play;
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
        type = BackgroundStore.VIDEO.equals(t) ? BackgroundStore.VIDEO
                : BackgroundStore.AUDIO.equals(t) ? BackgroundStore.AUDIO : BackgroundStore.IMAGE;
        isVideo = BackgroundStore.VIDEO.equals(type);
        isAudio = BackgroundStore.AUDIO.equals(type);
        if (isAudio) scope = BackgroundStore.HOME; // el audio solo existe en la Home
        navKey = scope + ":" + type;
        pl = BackgroundStore.load(this, scope, type);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // ---- Encabezado: atrás + título + estado
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
        int titleRes = isAudio ? R.string.audio_de : isVideo ? R.string.fondo_animado_de : R.string.fondo_de;
        title.setText(getString(titleRes, getString(
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

        // ---- Modo de ajuste: Rellenar · Estirar · Centrada (imagen y video)
        if (!isAudio) {
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
        }

        // ---- Videos y audios: anterior/siguiente, modos, sonido/volumen
        if (isVideo || isAudio) buildPlaybackControls();

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
        emptyView.setText(isAudio ? R.string.vacio_audios : isVideo ? R.string.vacio_videos : R.string.vacio_imagenes);
        emptyView.setTextColor(getColor(R.color.mut));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(40), 0, dp(40), 0);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Pie: Quitar (destructiva, secundaria) + Añadir (acción principal)
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(24), dp(8), dp(24), dp(20));
        TextView remove = BottomSheet.button(this, getString(isAudio ? R.string.quitar_audio
                : isVideo ? R.string.quitar_fondo_animado : R.string.quitar_fondo), false);
        remove.setTextColor(getColor(R.color.danger));
        remove.setOnClickListener(v -> clearActive());
        footer.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView add = BottomSheet.button(this, getString(isAudio ? R.string.anadir_audio
                : isVideo ? R.string.anadir_video : R.string.anadir_imagenes), true);
        add.setOnClickListener(v -> pickFiles());
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
                PlaylistNav.reset(navKey);
                BackgroundStore.save(PlaylistActivity.this, scope, type, pl); // el orden se guarda al soltar
            }
        });
        touch.attachToRecyclerView(list);

        setContentView(root);
        FontManager.apply(root);
        refreshAll();
    }

    // =========================================================================
    //  Controles de reproducción (video y audio)
    // =========================================================================

    private void buildPlaybackControls() {
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);

        prevBtn = iconButton(R.drawable.ic_prev, getString(R.string.anterior));
        prevBtn.setOnClickListener(v -> step(false));
        transport.addView(prevBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));

        nowPlaying = new TextView(this);
        nowPlaying.setTextColor(getColor(R.color.mut));
        nowPlaying.setTextSize(14);
        nowPlaying.setGravity(Gravity.CENTER);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.setMargins(dp(8), 0, dp(8), 0);
        transport.addView(nowPlaying, np);

        nextBtn = iconButton(R.drawable.ic_next, getString(R.string.siguiente));
        nextBtn.setOnClickListener(v -> step(true));
        transport.addView(nextBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trp.setMargins(dp(24), 0, dp(24), dp(4));
        root.addView(transport, trp);

        // Aleatorio · Repetir (la lista) · Bucle (el actual): dorados cuando están activos
        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        tShuffle = toggle(R.string.aleatorio, pl.shuffle, () -> {
            pl.shuffle = !pl.shuffle;
            PlaylistNav.reset(navKey);
        });
        tRepeat = toggle(R.string.repetir, pl.repeat, () -> pl.repeat = !pl.repeat);
        tLoop = toggle(R.string.bucle, pl.loop, () -> pl.loop = !pl.loop);
        toggles.addView(tShuffle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mid.leftMargin = dp(8);
        toggles.addView(tRepeat, mid);
        LinearLayout.LayoutParams last = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        last.leftMargin = dp(8);
        toggles.addView(tLoop, last);
        LinearLayout.LayoutParams tgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tgp.setMargins(dp(24), dp(4), dp(24), dp(2));
        root.addView(toggles, tgp);

        TextView hint = new TextView(this);
        hint.setText(isAudio ? R.string.ayuda_bucle_audio : R.string.ayuda_bucle);
        hint.setTextColor(getColor(R.color.mut));
        hint.setTextSize(12);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(dp(28), dp(4), dp(28), dp(4));
        root.addView(hint, hp);

        // Sonido (video) o volumen (audio)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (isVideo) {
            tSound = new TextView(this);
            tSound.setGravity(Gravity.CENTER);
            tSound.setMinHeight(dp(44));
            tSound.setTextSize(15);
            tSound.setClickable(true);
            tSound.setFocusable(true);
            tSound.setOnClickListener(v -> toggleSound());
            row.addView(tSound, new LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            volLabel = new TextView(this);
            volLabel.setTextColor(getColor(R.color.mut));
            volLabel.setTextSize(14);
            row.addView(volLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(pl.volume);
        ColorStateList gold = ColorStateList.valueOf(getColor(R.color.gold));
        seek.setProgressTintList(gold);
        seek.setThumbTintList(gold);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                pl.volume = progress;
                // Como en Voltea: subir el volumen por encima de 0 con el video en silencio quita el silencio
                if (isVideo && progress > 0 && pl.mute) pl.mute = false;
                refreshSound();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                BackgroundStore.save(PlaylistActivity.this, scope, type, pl);
            }
        });
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        skp.leftMargin = dp(12);
        row.addView(seek, skp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(dp(24), dp(6), dp(24), dp(2));
        root.addView(row, rp);

        // Recordatorio de la regla música ↔ video (en el video solo tiene sentido en la Home)
        hintExtra = new TextView(this);
        hintExtra.setTextColor(getColor(R.color.mut));
        hintExtra.setTextSize(12);
        LinearLayout.LayoutParams hxp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hxp.setMargins(dp(28), dp(2), dp(28), dp(4));
        root.addView(hintExtra, hxp);
    }

    private ImageView iconButton(int res, String description) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setContentDescription(description);
        return b;
    }

    private TextView toggle(int label, boolean on, Runnable change) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(44));
        t.setTextSize(15);
        t.setClickable(true);
        t.setFocusable(true);
        UiKit.styleToggle(t, on);
        t.setOnClickListener(v -> {
            change.run();
            BackgroundStore.save(this, scope, type, pl);
            UiKit.styleToggle(tShuffle, pl.shuffle);
            UiKit.styleToggle(tRepeat, pl.repeat);
            UiKit.styleToggle(tLoop, pl.loop);
        });
        return t;
    }

    /** ¿El video suena? (activo, sin silencio y con volumen > 0), como vidSnd() en Voltea. */
    private boolean videoSounds() {
        return pl.current() != null && !pl.mute && pl.volume > 0;
    }

    /** Silenciar / Activar sonido del video. */
    private void toggleSound() {
        if (!isVideo) return;
        if (!pl.mute && pl.volume > 0) {
            pl.mute = true;
        } else {
            pl.mute = false;
            if (pl.volume == 0) {
                pl.volume = 100;
                seek.setProgress(100);
            }
        }
        BackgroundStore.save(this, scope, type, pl);
        refreshSound();
    }

    private void refreshSound() {
        if (isVideo) {
            boolean sounds = !pl.mute && pl.volume > 0;
            tSound.setText(sounds ? R.string.silenciar : R.string.activar_sonido);
            UiKit.styleToggle(tSound, sounds);
            FontManager.applyTo(tSound);
        } else if (isAudio) {
            volLabel.setText(getString(R.string.volumen) + " " + pl.volume + "%");
        }
        updateHintExtra();
    }

    private void updateHintExtra() {
        if (hintExtra == null) return;
        if (isVideo && BackgroundStore.HOME.equals(scope)) {
            hintExtra.setText(videoSounds() ? R.string.hint_video_suena : R.string.hint_video_silencio);
            hintExtra.setVisibility(View.VISIBLE);
        } else if (isAudio) {
            hintExtra.setText(BackgroundStore.homeVideoSounds(this)
                    ? R.string.hint_audio_bloqueado : R.string.ayuda_audio_home);
            hintExtra.setVisibility(View.VISIBLE);
        } else {
            hintExtra.setVisibility(View.GONE);
        }
        FontManager.applyTo(hintExtra);
    }

    private int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    // =========================================================================
    //  Encabezado y estado
    // =========================================================================

    private void refreshAll() {
        refreshHeader();
        if (isVideo || isAudio) refreshSound();
    }

    private void refreshHeader() {
        BackgroundStore.Item cur = pl.current();
        if (isAudio) {
            if (cur == null) {
                subtitle.setText(R.string.sin_audio);
            } else if (pl.off) {
                subtitle.setText(getString(R.string.audio_en_pausa, cur.name));
            } else if (BackgroundStore.homeVideoSounds(this)) {
                subtitle.setText(getString(R.string.audio_bloqueado, cur.name));
            } else {
                subtitle.setText(getString(R.string.audio_reproduciendo, cur.name));
            }
        } else if (cur != null) {
            subtitle.setText(getString(R.string.aplicado, cur.name));
        } else if (isVideo) {
            subtitle.setText(R.string.sin_animado);
        } else {
            subtitle.setText(BackgroundStore.HOME.equals(scope)
                    ? R.string.sin_fondo_sistema : R.string.sin_fondo_drawer);
        }
        emptyView.setVisibility(pl.items.isEmpty() ? View.VISIBLE : View.GONE);
        if (isVideo || isAudio) {
            nowPlaying.setText(cur != null ? getString(R.string.reproduciendo, cur.name)
                    : getString(isAudio ? R.string.ninguno_audio : R.string.ninguno_activo));
            // como en Voltea: anterior/siguiente solo con más de un elemento
            int vis = pl.items.size() > 1 ? View.VISIBLE : View.INVISIBLE;
            prevBtn.setVisibility(vis);
            nextBtn.setVisibility(vis);
        }
    }

    private String metaOf(BackgroundStore.Item it) {
        return it.artist.isEmpty() ? it.ext() : it.artist + " · " + it.ext();
    }

    // =========================================================================
    //  Lista
    // =========================================================================

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
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
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

            // Audio: botón redondo ▶/⏸ solo en el actual
            ImageView play = new ImageView(PlaylistActivity.this);
            play.setScaleType(ImageView.ScaleType.CENTER);
            play.setBackgroundResource(R.drawable.play_btn_bg);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(40), dp(40));
            pp.rightMargin = dp(4);
            row.addView(play, pp);

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

            return new RowVH(row, thumb, name, meta, check, play, trash, grip);
        }

        @Override
        public void onBindViewHolder(RowVH h, int position) {
            BackgroundStore.Item it = pl.items.get(position);
            boolean current = it.id.equals(pl.cur);
            h.row.setSelected(current);
            h.name.setText(it.name);
            h.name.setTextColor(getColor(current ? R.color.gold_hi : R.color.ink));
            h.meta.setText(metaOf(it));

            // Audio: ▶/⏸ en el actual; resto de tipos: palomita
            h.check.setVisibility(current && !isAudio ? View.VISIBLE : View.GONE);
            if (isAudio && current) {
                boolean playing = !pl.off && !BackgroundStore.homeVideoSounds(PlaylistActivity.this);
                h.play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                h.play.setContentDescription(getString(playing ? R.string.pausar : R.string.reproducir));
                h.play.setVisibility(View.VISIBLE);
                h.play.setOnClickListener(v -> togglePlay());
            } else {
                h.play.setVisibility(View.GONE);
            }

            if (isAudio) {
                java.io.File cover = BackgroundStore.thumbFile(PlaylistActivity.this, type, it);
                if (cover.isFile()) {
                    h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    ImageUtil.loadThumb(cover, h.thumb, dp(52));
                } else {
                    h.thumb.setTag(null);
                    h.thumb.setScaleType(ImageView.ScaleType.CENTER); // sin carátula: nota musical
                    h.thumb.setImageResource(R.drawable.ic_note);
                }
            } else if (isVideo) {
                h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ImageUtil.loadVideoThumb(BackgroundStore.fileOf(PlaylistActivity.this, type, it),
                        BackgroundStore.thumbFile(PlaylistActivity.this, it), h.thumb, dp(52));
            } else {
                h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ImageUtil.loadThumb(BackgroundStore.fileOf(PlaylistActivity.this, type, it), h.thumb, dp(52));
            }

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

    // =========================================================================
    //  Acciones
    // =========================================================================

    private void applyItem(BackgroundStore.Item it) {
        if (isAudio) {
            selectAudio(it);
            return;
        }
        if (it.id.equals(pl.cur)) return;
        if (isVideo) PlaylistNav.picked(navKey, pl, it.id);
        pl.cur = it.id;
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshAll();
        Toast.makeText(this, isVideo ? R.string.fondo_animado_aplicado : R.string.fondo_aplicado,
                Toast.LENGTH_SHORT).show();
    }

    // ---- Audio: reglas de Voltea ----------------------------------------------------

    /** Intentar reproducir. Si el video de fondo suena no puede iniciarse: se avisa y queda en pausa. */
    private void tryPlay() {
        if (BackgroundStore.homeVideoSounds(this)) {
            Toast.makeText(this, R.string.silencia_video, Toast.LENGTH_LONG).show();
        } else {
            pl.off = false; // ▶ limpia la pausa a mano
        }
    }

    /** Tocar una canción: pasa a ser la actual y se intenta reproducir. */
    private void selectAudio(BackgroundStore.Item it) {
        if (it.id.equals(pl.cur)) return;
        PlaylistNav.picked(navKey, pl, it.id);
        pl.cur = it.id;
        pl.pos = 0;
        tryPlay();
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshAll();
    }

    /** ▶/⏸ de la canción actual. ⏸ = pausa a mano: no se reanuda sola hasta que se vuelva a pulsar ▶. */
    private void togglePlay() {
        if (pl.current() == null) return;
        if (pl.off) {
            if (BackgroundStore.homeVideoSounds(this)) {
                Toast.makeText(this, R.string.silencia_video, Toast.LENGTH_LONG).show();
                return;
            }
            pl.off = false;
        } else {
            pl.off = true;
        }
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshAll();
    }

    /** Anterior / siguiente (siempre dan la vuelta a la lista; con Aleatorio siguen el barajado). */
    private void step(boolean forward) {
        if (isAudio && !forward && pl.pos > 3000) {
            // Como en Voltea: con más de 3 s reproducidos, "anterior" reinicia la canción actual
            pl.pos = 0;
            tryPlay();
            BackgroundStore.save(this, scope, type, pl);
            adapter.notifyDataSetChanged();
            refreshAll();
            return;
        }
        BackgroundStore.Item n = forward
                ? PlaylistNav.next(navKey, pl, false)
                : PlaylistNav.prev(navKey, pl);
        if (n == null) return;
        pl.cur = n.id;
        if (isAudio) {
            pl.pos = 0;
            tryPlay();
        }
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshAll();
    }

    private void clearActive() {
        if (pl.cur.isEmpty()) return;
        pl.cur = ""; // la lista se conserva; solo se desactiva
        pl.pos = 0;
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshAll();
        Toast.makeText(this, isAudio ? R.string.audio_quitado
                : isVideo ? R.string.fondo_animado_quitado : R.string.fondo_quitado, Toast.LENGTH_SHORT).show();
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
        boolean wasCurrent = it.id.equals(pl.cur);
        pl.items.remove(idx);
        if (wasCurrent) {
            // Imagen: vuelve el fondo por defecto. Video/audio: sigue con el que ocupó su lugar
            // (si era el último, se quita).
            if ((isVideo || isAudio) && !pl.items.isEmpty()) {
                pl.cur = pl.items.get(Math.min(idx, pl.items.size() - 1)).id;
            } else {
                pl.cur = "";
            }
            pl.pos = 0;
        }
        PlaylistNav.reset(navKey);
        BackgroundStore.deleteFile(this, type, it);
        BackgroundStore.save(this, scope, type, pl);
        adapter.notifyDataSetChanged();
        refreshAll();
    }

    // =========================================================================
    //  Añadir desde el dispositivo
    // =========================================================================

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(isAudio ? "audio/*" : isVideo ? "video/*" : "image/*");
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
                if (wasEmpty && !added.isEmpty()) {
                    pl.cur = added.get(0).id; // el primero pasa a ser el actual
                    pl.pos = 0;
                    if (isAudio) tryPlay();   // y empieza a sonar si las reglas lo permiten
                }
                if (!added.isEmpty()) {
                    PlaylistNav.reset(navKey);
                    BackgroundStore.save(this, scope, type, pl);
                }
                adapter.notifyDataSetChanged();
                refreshAll();
                if (!added.isEmpty()) {
                    Toast.makeText(this, getResources().getQuantityString(
                            isAudio ? R.plurals.audios_anadidos
                                    : isVideo ? R.plurals.videos_anadidos : R.plurals.imagenes_anadidas,
                            added.size(), added.size()), Toast.LENGTH_SHORT).show();
                    list.scrollToPosition(pl.items.size() - 1);
                }
                if (failedCount > 0) {
                    Toast.makeText(this, isAudio ? R.string.audio_no_leido
                            : isVideo ? R.string.video_no_leido : R.string.imagen_no_leida,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private int dp(int v) {
        return UiKit.dp(this, v);
    }
}
