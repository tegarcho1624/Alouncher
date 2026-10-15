package com.alouncher.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Una sola Activity con dos capas en la misma ventana:
 *  - Home (fondo de pantalla + cuadrícula + dock)
 *  - Cajón de apps (lo que era la pantalla de la Fase 1/2), que sube sobre la Home.
 * Estar en la misma ventana es lo que permite arrastrar del cajón a la Home.
 */
public class MainActivity extends Activity {

    private static final int DRAWER_COLUMNS = 4;
    private static final int REQ_PICK_ICON = 1001;
    private static final String STATE_PENDING_KEY = "pendingIconKey";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    private CustomizationStore store;
    private HomeStore homeStore;

    // Home (páginas deslizables + dock)
    private static final int PAGE_BOTTOM_PAD_DP = 148;
    private View homeLayer;
    private RecyclerView pager;
    private PagesAdapter pagesAdapter;
    private PageIndicator indicator;
    private TextView homeHint;
    private CappedLinearLayout dock;
    private View homeTrash;
    private final FrameLayout[] dockSlots = new FrameLayout[HomeStore.DOCK_SLOTS];
    private LongPressDrag placedDrag;
    private View hot; // celda o espacio resaltado durante un arrastre
    private int currentIndex;  // página que se está viendo
    private int edgeDir;       // -1 / 0 / 1: ícono arrastrado junto al borde -> cambia de página
    private final Runnable edgeTask = this::edgeTick;

    // Modo edición (páginas como tarjetas)
    private View editLayer;
    private RecyclerView editPager;
    private EditAdapter editAdapter;
    private PageIndicator editIndicator;
    private View editTrash;
    private View editHint;
    private LongPressDrag editDrag;
    private View editHot;
    private int editCardW;
    private int editEdgeDir;
    private boolean editMode;
    private final Runnable editEdgeTask = this::editEdgeTick;

    // Cajón
    private View drawerLayer;
    private View drawerScrim;
    private DrawerSheet drawerSheet;
    private RecyclerView grid;
    private AppAdapter adapter;
    private boolean drawerOpen;

    private PopupWindow menu;
    private BottomSheet sheet;
    private BroadcastReceiver pkgReceiver;

    private Map<String, AppEntry> allApps = new HashMap<>(); // visibles + ocultas, por clave
    private List<AppEntry> hiddenApps = new ArrayList<>();
    private boolean loaded;
    private BottomSheet folderSheet;   // panel de la carpeta abierta
    private String openFolderId;
    private int fontApplied = -1; // versión de la fuente que ya está aplicada a las vistas
    private BackdropView homeBackdrop;   // fondo de la Home
    private BackdropView drawerBackdrop; // fondo del cajón
    private final ExecutorService bgIo = Executors.newSingleThreadExecutor();
    private int bgApplied = -1;          // versión de los fondos que ya está aplicada
    private boolean resumed;             // la Activity está a la vista (los videos solo corren entonces)
    private boolean windowFocused;       // sin menús, diálogos ni paneles encima
    private boolean bgReady;             // ya se aplicaron los fondos al menos una vez
    private AudioPlayer audio;           // audio de la Home
    private BackgroundStore.Playlist audioPl;
    private boolean audioPlaying;
    private boolean audioEnded;          // la lista llegó al final sin Repetir: queda detenida
    private int audioFails;
    private final AudioPlayer.Host audioHost = new AudioPlayer.Host() {
        @Override public void onPrepared() { audioFails = 0; }
        @Override public void onEnded() { onAudioEnded(); }
        @Override public void onError() { onAudioError(); }
    };
    private final VideoLayer.Listener homeVideoListener = new VideoLayer.Listener() {
        @Override public void onEnded() { onVideoEnded(BackgroundStore.HOME); }
        @Override public void onError() { onVideoError(BackgroundStore.HOME); }
    };
    private final VideoLayer.Listener drawerVideoListener = new VideoLayer.Listener() {
        @Override public void onEnded() { onVideoEnded(BackgroundStore.DRAWER); }
        @Override public void onError() { onVideoError(BackgroundStore.DRAWER); }
    };
    private boolean pruneOnNextLoad = true;
    private String pendingIconKey; // entrada (paquete/actividad) a la que se le elige ícono

    /** instanceId == null: viene del cajón (se crea una instancia nueva). Si no: se mueve esa instancia. */
    private static final class DragPayload {
        final String key;
        final String instanceId;
        final String folderId; // si no es null, lo que se arrastra es una carpeta
        DragPayload(String key, String instanceId, String folderId) {
            this.key = key;
            this.instanceId = instanceId;
            this.folderId = folderId;
        }
    }

    /** Etiqueta de una carpeta cerrada en la Home. */
    private static final class FolderRef {
        final String id;
        FolderRef(String id) { this.id = id; }
    }

    /** Lo que lleva como etiqueta cada ícono colocado en la Home o el dock. */
    private static final class Placed {
        final AppEntry app;
        final String instanceId;
        Placed(AppEntry app, String instanceId) {
            this.app = app;
            this.instanceId = instanceId;
        }
    }

    private static final class PageDragPayload {
        final int id;
        PageDragPayload(int id) { this.id = id; }
    }

    private static final class Target {
        final boolean dock;
        final int page;
        final int row;
        final int col;
        final View view;
        Target(boolean dock, int page, int row, int col, View view) {
            this.dock = dock;
            this.page = page;
            this.row = row;
            this.col = col;
            this.view = view;
        }
    }

    /** Una página de la Home: cuadrícula de celdas. */
    private static final class PageVH extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final FrameLayout[][] cells = new FrameLayout[HomeStore.HOME_ROWS][HomeStore.HOME_COLS];
        PageVH(LinearLayout root) {
            super(root);
            this.root = root;
        }
    }

    /** Una tarjeta del modo edición. */
    private static final class CardVH extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final PageThumb thumb;
        final ImageView house;
        CardVH(FrameLayout card, PageThumb thumb, ImageView house) {
            super(card);
            this.card = card;
            this.thumb = thumb;
            this.house = house;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FontManager.init(this);
        setContentView(R.layout.activity_main);
        store = new CustomizationStore(this);
        homeStore = new HomeStore(this);
        if (savedInstanceState != null) {
            pendingIconKey = savedInstanceState.getString(STATE_PENDING_KEY);
        }

        homeLayer = findViewById(R.id.home_layer);
        pager = findViewById(R.id.home_pager);
        indicator = findViewById(R.id.page_indicator);
        homeHint = findViewById(R.id.home_hint);
        dock = findViewById(R.id.dock);
        homeTrash = findViewById(R.id.home_trash);
        drawerLayer = findViewById(R.id.drawer_layer);
        drawerScrim = findViewById(R.id.drawer_scrim);
        drawerSheet = findViewById(R.id.drawer_sheet);
        homeBackdrop = findViewById(R.id.home_bg);
        drawerBackdrop = findViewById(R.id.drawer_bg);
        drawerBackdrop.setTopCornerRadius(dp(32)); // sigue la forma redondeada del cajón
        grid = findViewById(R.id.grid);

        dock.setMaxWidth(dp(720));
        drawerSheet.setMaxWidth(dp(960));

        // ---- Home y dock
        placedDrag = new LongPressDrag(this, new LongPressDrag.Listener() {
            @Override public void onLongPress(View v) {
                Object t = v.getTag();
                if (t instanceof Placed) showPlacedMenu(v, (Placed) t);
                else if (t instanceof FolderRef) showFolderMenu(v, ((FolderRef) t).id);
            }
            @Override public void onDragStart(View v) {
                Object t = v.getTag();
                if (t instanceof Placed) {
                    beginDrag(v, ((Placed) t).app.key(), ((Placed) t).instanceId);
                } else if (t instanceof FolderRef) {
                    beginFolderDrag(v, ((FolderRef) t).id);
                }
            }
        });
        buildDock();
        buildPager();
        buildEditMode();
        homeLayer.setOnDragListener(this::onHomeDrag);

        // ---- Cajón (misma lógica de siempre)
        grid.setLayoutManager(new GridLayoutManager(this, DRAWER_COLUMNS));
        adapter = new AppAdapter(new AppAdapter.Listener() {
            @Override public void onOpen(AppEntry app) {
                openApp(app);
                closeDrawer(false, false);
            }
            @Override public void onMenu(View anchor, AppEntry app) { showMenu(anchor, app); }
            @Override public void onDragStart(View anchor, AppEntry app) { beginDrag(anchor, app.key(), null); }
            @Override public void onHiddenApps() { showHiddenSheet(); }
        });
        grid.setAdapter(adapter);

        drawerSheet.bind(grid, new DrawerSheet.Callback() {
            @Override public void onPull(float fraction) { drawerScrim.setAlpha(1f - fraction); }
            @Override public void onDismiss() { closeDrawer(true, false); }
        });
        drawerScrim.setOnClickListener(v -> closeDrawer(true, false));
        findViewById(R.id.drawer_close).setOnClickListener(v -> closeDrawer(true, false));

        // Mantiene todo al día cuando instalas, actualizas o desinstalas algo.
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                // Desinstalación definitiva (no actualización): olvidar personalización y lugar en Home/dock.
                if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(i.getAction()) && i.getData() != null) {
                    forget(i.getData().getSchemeSpecificPart());
                }
                ui.removeCallbacks(reloadTask);
                ui.postDelayed(reloadTask, 400); // una actualización dispara quitar+agregar
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        FontManager.apply(getWindow().getDecorView()); // textos fijos (cajón, modo edición, mensaje)
        fontApplied = FontManager.version();

        audio = new AudioPlayer(audioHost);
        applyBackgrounds();
        reload();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_KEY, pendingIconKey);
    }

    @Override
    protected void onPause() {
        resumed = false;
        syncVideoPlayback(); // al perder el foco los videos se pausan (batería)
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Primero se vuelven a leer listas y ajustes (video y audio) y DESPUÉS se reanuda nada
        if (bgApplied != BackgroundStore.version()) applyBackgrounds();
        syncVideoPlayback();
        if (fontApplied != FontManager.version()) {
            fontApplied = FontManager.version();
            FontManager.apply(getWindow().getDecorView());
            adapter.notifyDataSetChanged(); // nombres del cajón
            renderHome();                   // nombres de la Home
        }
    }

    /**
     * Se llama al pulsar HOME. En el cajón: lo cierra y vuelve a la Home.
     * Ya en la Home: no hace nada.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissMenu();
        dismissSheet();
        dismissFolderPanel();
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            if (drawerOpen) closeDrawer(true, false);
            if (editMode) {
                currentIndex = Math.max(0, homeStore.mainIndex());
                exitEditMode(true);
            } else {
                goToPage(Math.max(0, homeStore.mainIndex()), true); // HOME siempre lleva a la principal
            }
        }
    }

    /** "Atrás": cierra el cajón si está abierto; en la Home no hace nada. */
    @Override
    public void onBackPressed() {
        if (menu != null) {
            dismissMenu();
        } else if (editMode) {
            exitEditMode(true);
        } else if (drawerOpen) {
            closeDrawer(true, false);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(reloadTask);
        dismissMenu();
        dismissSheet();
        dismissFolderPanel();
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        io.shutdownNow();
        bgIo.shutdownNow();
        homeBackdrop.clearVideo();
        drawerBackdrop.clearVideo();
        if (audio != null) audio.release();
        super.onDestroy();
    }

    // =========================================================================
    //  Lista de apps + personalizaciones
    // =========================================================================

    private void reload() {
        if (io.isShutdown()) return;
        final int iconPx = dp(64);
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);

            // Migración: personalizaciones guardadas antes solo por paquete.
            Map<String, List<String>> byPkg = new HashMap<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                byPkg.computeIfAbsent(pkg, k -> new ArrayList<>())
                        .add(pkg + "/" + ri.activityInfo.name);
            }
            for (Map.Entry<String, List<String>> en : byPkg.entrySet()) {
                store.migrate(en.getKey(), en.getValue());
            }

            List<AppEntry> visible = new ArrayList<>();
            List<AppEntry> hidden = new ArrayList<>();
            Map<String, AppEntry> all = new HashMap<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue; // no mostrarse a sí mismo

                Customization c = store.get(pkg + "/" + ri.activityInfo.name);
                // Si la imagen ya no existe, load() devuelve null y se usa el ícono original.
                Drawable custom = c.icon != null ? IconLoader.load(this, c.icon, iconPx) : null;

                AppEntry e = new AppEntry(
                        pkg,
                        new ComponentName(pkg, ri.activityInfo.name),
                        ri.loadLabel(pm).toString(),
                        ri.loadIcon(pm),
                        c.name, c.icon, custom,
                        c.hidden);
                all.put(e.key(), e);
                if (c.hidden) hidden.add(e); else visible.add(e);
            }
            Collator col = Collator.getInstance();
            visible.sort((a, b) -> col.compare(a.label(), b.label()));
            hidden.sort((a, b) -> col.compare(a.label(), b.label()));

            ui.post(() -> {
                if (isDestroyed()) return;
                hiddenApps = hidden;
                allApps = all;
                // Al abrir el launcher: limpiar lo que ya no está instalado (no durante actualizaciones).
                if (pruneOnNextLoad) {
                    homeStore.pruneMissing(all.keySet());
                    pruneOnNextLoad = false;
                }
                loaded = true;
                adapter.submit(visible, !hidden.isEmpty());
                renderHome();
            });
        });
    }

    // =========================================================================
    //  Fondos (Fondos y temas): imagen elegida para la Home y para el cajón
    // =========================================================================

    private void applyBackgrounds() {
        if (bgIo.isShutdown()) return;
        bgApplied = BackgroundStore.version();
        applyAudio();
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        final Context app = getApplicationContext();
        bgIo.execute(() -> {
            // Si el archivo ya no existe, resolveCurrent salta al siguiente de la lista (o a ninguno)
            File hf = BackgroundStore.resolveCurrent(app, BackgroundStore.HOME, BackgroundStore.IMAGE);
            Bitmap hb = hf != null ? ImageUtil.decodeSampled(hf, w, h) : null;
            String hMode = BackgroundStore.load(app, BackgroundStore.HOME, BackgroundStore.IMAGE).mode;

            File df = BackgroundStore.resolveCurrent(app, BackgroundStore.DRAWER, BackgroundStore.IMAGE);
            Bitmap db = df != null ? ImageUtil.decodeSampled(df, w, h) : null;
            String dMode = BackgroundStore.load(app, BackgroundStore.DRAWER, BackgroundStore.IMAGE).mode;

            // Fondos animados (mismo criterio: si el archivo falta, salta al siguiente que exista)
            File hv = BackgroundStore.resolveCurrent(app, BackgroundStore.HOME, BackgroundStore.VIDEO);
            BackgroundStore.Playlist hvp = BackgroundStore.load(app, BackgroundStore.HOME, BackgroundStore.VIDEO);
            File dv = BackgroundStore.resolveCurrent(app, BackgroundStore.DRAWER, BackgroundStore.VIDEO);
            BackgroundStore.Playlist dvp = BackgroundStore.load(app, BackgroundStore.DRAWER, BackgroundStore.VIDEO);

            ui.post(() -> {
                if (isDestroyed()) return;
                showHomeBackground(hb, hMode);
                if (db != null) drawerBackdrop.showImage(db, dMode); else drawerBackdrop.clear();
                // El animado REEMPLAZA al estático mientras esté activo; el estático sigue guardado
                showVideoFor(BackgroundStore.HOME, hv, hvp);
                showVideoFor(BackgroundStore.DRAWER, dv, dvp);
                updateBars();
                bgReady = true;
                syncVideoPlayback();
            });
        });
    }

    private void showHomeBackground(Bitmap bmp, String mode) {
        if (bmp != null) {
            homeBackdrop.showImage(bmp, mode);
        } else {
            homeBackdrop.clear(); // vuelve el fondo de pantalla del sistema
        }
    }

    /** Con fondo propio (imagen o video) las barras del sistema van en el color base, como en Voltea; si no, transparentes. */
    private void updateBars() {
        int bar = homeBackdrop.hasMedia() ? getColor(R.color.bg) : Color.TRANSPARENT;
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);
    }

    // ---- Fondo animado -----------------------------------------------------------

    private void showVideoFor(String scope, File f, BackgroundStore.Playlist p) {
        boolean home = BackgroundStore.HOME.equals(scope);
        BackdropView b = home ? homeBackdrop : drawerBackdrop;
        if (f != null) {
            // Silenciado por defecto y sin controles; en bucle según el ajuste de la lista
            b.showVideo(f, p.mode, p.loop, p.mute ? 0f : p.volume / 100f,
                    home ? homeVideoListener : drawerVideoListener);
        } else {
            b.clearVideo(); // vuelve a verse el fondo estático (o el predeterminado)
        }
    }

    /** Solo corre el video del destino que está a la vista: la Home, o el cajón cuando está abierto. */
    private void syncVideoPlayback() {
        homeBackdrop.setVideoPlaying(resumed && !drawerOpen);
        drawerBackdrop.setVideoPlaying(resumed && drawerOpen);
        syncAudio();
    }

    /** Terminó un video (solo ocurre sin Bucle): pasa al siguiente de la lista según Aleatorio / Repetir. */
    private void onVideoEnded(String scope) {
        BackgroundStore.Playlist p = BackgroundStore.load(this, scope, BackgroundStore.VIDEO);
        BackgroundStore.Item n = PlaylistNav.next(scope + ":" + BackgroundStore.VIDEO, p, true);
        if (n == null) return; // sin Repetir, la lista se detiene en el último
        boolean same = n.id.equals(p.cur);
        p.cur = n.id;
        BackgroundStore.saveNoBump(this, scope, BackgroundStore.VIDEO, p);
        BackdropView b = BackgroundStore.HOME.equals(scope) ? homeBackdrop : drawerBackdrop;
        if (same) {
            b.restartVideo(); // lista de un solo video con Repetir
            return;
        }
        File f = BackgroundStore.fileOf(this, BackgroundStore.VIDEO, n);
        if (!f.isFile()) {
            applyBackgrounds(); // ese ya no existe: se vuelve a resolver y salta al siguiente que exista
            return;
        }
        showVideoFor(scope, f, p);
        syncVideoPlayback();
    }

    /** El video no se pudo reproducir: se quita de pantalla y queda el estático o el color por defecto, sin errores. */
    private void onVideoError(String scope) {
        BackdropView b = BackgroundStore.HOME.equals(scope) ? homeBackdrop : drawerBackdrop;
        b.clearVideo();
        updateBars();
        syncAudio(); // si el video fallaba con sonido, el audio puede retomar (si no estaba pausado a mano)
    }

    // =========================================================================
    //  Audio de la Home y su coexistencia con el fondo animado (reglas de Voltea)
    //
    //  El audio suena SOLO si: está en la Home (a la vista, con foco, sin menús ni cajón ni modo
    //  edición encima) · hay un audio activo · no está pausado a mano · NO suena el fondo animado.
    //  Todo se decide desde syncAudio(), a partir del estado guardado, y se vuelve a evaluar cada vez que
    //  algo cambia (foco, cajón, edición, videos, listas).
    // =========================================================================

    /** "Estar en la Home": a la vista y con el foco, sin cajón ni modo edición. Un menú o diálogo quita el foco. */
    private boolean homeActive() {
        return resumed && windowFocused && !drawerOpen && !editMode;
    }

    /**
     * vidSnd() de Voltea: hay fondo animado activo en la Home, NO está silenciado y su volumen es > 0.
     * Solo entonces "el video suena". Si el video falló o se quitó, ya no cuenta.
     */
    private boolean videoSounds() {
        BackgroundStore.Playlist p = BackgroundStore.load(this, BackgroundStore.HOME, BackgroundStore.VIDEO);
        if (p.mute || p.volume <= 0) return false;
        return bgReady ? homeBackdrop.hasVideo() : p.current() != null;
    }

    private String audioKey() {
        return BackgroundStore.HOME + ":" + BackgroundStore.AUDIO;
    }

    /** Vuelve a leer la lista de audio y su ajuste. Si el archivo actual ya no existe, salta al siguiente que exista. */
    private void applyAudio() {
        if (audio == null) return;
        BackgroundStore.Playlist before = BackgroundStore.load(this, BackgroundStore.HOME, BackgroundStore.AUDIO);
        File f = BackgroundStore.resolveCurrent(this, BackgroundStore.HOME, BackgroundStore.AUDIO);
        BackgroundStore.Playlist p = BackgroundStore.load(this, BackgroundStore.HOME, BackgroundStore.AUDIO);
        if (!before.cur.equals(p.cur)) p.pos = 0; // saltó a otro archivo: empieza desde el principio
        audioPl = p;
        if (f != null) {
            audio.setSource(f, p.loop, p.volume / 100f, p.pos);
        } else {
            audio.clear(); // sin audio (o ninguno existe): nada que reproducir, sin errores
        }
        audioEnded = false;
        audioFails = 0;
        syncAudio();
    }

    /** Decide si el audio suena o está en pausa. Se guarda dónde quedó al dejar de sonar. */
    private void syncAudio() {
        if (audio == null) return;
        boolean play = audioPl != null
                && audioPl.current() != null
                && !audioPl.off        // pausado a mano: no se reanuda solo
                && !audioEnded
                && homeActive()        // solo en la Home
                && !videoSounds();     // nunca mientras el fondo animado suene
        audio.setPlaying(play);
        if (audioPlaying && !play) persistAudioPos();
        audioPlaying = play;
    }

    private void persistAudioPos() {
        if (audioPl == null || !audio.hasSource()) return;
        audioPl.pos = audio.position();
        BackgroundStore.saveNoBump(this, BackgroundStore.HOME, BackgroundStore.AUDIO, audioPl);
    }

    /** Terminó un audio (solo ocurre sin Bucle): pasa al siguiente según Aleatorio / Repetir; si no hay, la lista se detiene. */
    private void onAudioEnded() {
        if (audioPl == null) return;
        BackgroundStore.Item n = PlaylistNav.next(audioKey(), audioPl, true);
        if (n == null) {
            audioEnded = true;
            audioPl.pos = 0;
            BackgroundStore.saveNoBump(this, BackgroundStore.HOME, BackgroundStore.AUDIO, audioPl);
            syncAudio();
            return;
        }
        boolean same = n.id.equals(audioPl.cur);
        audioPl.cur = n.id;
        audioPl.pos = 0;
        BackgroundStore.saveNoBump(this, BackgroundStore.HOME, BackgroundStore.AUDIO, audioPl);
        if (same) {
            audio.restart(); // lista de un solo audio con Repetir
            return;
        }
        playCurrentAudio();
    }

    /** Si un audio falla: aviso y salta al siguiente (como máximo una vuelta completa por la lista). */
    private void onAudioError() {
        Toast.makeText(this, R.string.audio_error, Toast.LENGTH_SHORT).show();
        if (audioPl == null || audioPl.items.isEmpty()) return;
        audioFails++;
        if (audioFails > audioPl.items.size()) {
            audioEnded = true;
            return;
        }
        BackgroundStore.Item n = PlaylistNav.next(audioKey(), audioPl, false);
        if (n == null || n.id.equals(audioPl.cur)) {
            audioEnded = true;
            return;
        }
        audioPl.cur = n.id;
        audioPl.pos = 0;
        BackgroundStore.saveNoBump(this, BackgroundStore.HOME, BackgroundStore.AUDIO, audioPl);
        playCurrentAudio();
    }

    private void playCurrentAudio() {
        BackgroundStore.Item cur = audioPl.current();
        File f = cur != null ? BackgroundStore.fileOf(this, BackgroundStore.AUDIO, cur) : null;
        if (f == null || !f.isFile()) {
            applyAudio(); // ese ya no existe: se vuelve a resolver y salta al siguiente que exista
            return;
        }
        audio.setSource(f, audioPl.loop, audioPl.volume / 100f, 0);
        syncAudio();
    }

    /** Un menú, diálogo o panel encima de la Home quita el foco: el audio se pausa; al volver, continúa. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        syncAudio();
    }

    // =========================================================================
    //  Carpetas
    // =========================================================================

    private String folderDisplayName(HomeStore.Folder f) {
        return f.name.isEmpty() ? getString(R.string.carpeta) : f.name;
    }

    /** Carpeta cerrada: del tamaño de un ícono, con las miniaturas de las primeras apps y su nombre debajo. */
    private View makeFolderItem(HomeStore.Folder fo, FrameLayout cell) {
        View item = getLayoutInflater().inflate(R.layout.item_app, cell, false);
        ((FrameLayout.LayoutParams) item.getLayoutParams()).gravity = Gravity.CENTER;
        ImageView icon = item.findViewById(R.id.icon);
        TextView label = item.findViewById(R.id.label);

        List<Drawable> minis = new ArrayList<>();
        for (HomeStore.Member m : fo.members) {
            AppEntry a = allApps.get(m.key);
            if (a == null) continue; // desinstalada o aún cargando
            minis.add(a.newIcon(getResources()));
            if (minis.size() == 4) break;
        }
        icon.setImageDrawable(new FolderDrawable(minis, dp(64)));
        label.setText(folderDisplayName(fo));
        label.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        FontManager.applyTo(label);

        item.setTag(new FolderRef(fo.id));
        item.setOnClickListener(v -> showFolderPanel(fo.id));
        placedDrag.attach(item);
        return item;
    }

    /**
     * Reglas al soltar (Home):
     *  - app sobre otra app  -> se crea una carpeta con ambas, en el lugar de la receptora
     *  - app sobre carpeta   -> se añade a ella
     *  - carpeta sobre carpeta -> las apps de la primera pasan a la segunda
     *  - carpeta sobre app   -> intercambian lugares
     *  - celda libre         -> app nueva (desde el cajón) o mover la existente
     * El dock sigue como antes (sin carpetas).
     */
    private int applyDrop(DragPayload p, Target t) {
        if (p.folderId != null) {
            if (t.dock) return HomeStore.OK; // las carpetas no van al dock
            return homeStore.moveFolder(p.folderId, t.page, t.row, t.col);
        }
        if (!t.dock) {
            HomeStore.Folder fo = homeStore.folderAt(t.page, t.row, t.col);
            if (fo != null) return homeStore.dropOnFolder(p.key, p.instanceId, fo.id);
            HomeStore.Instance occ = homeStore.instanceAt(false, t.page, t.row, t.col);
            if (occ != null) {
                if (occ.id.equals(p.instanceId)) return HomeStore.OK; // soltada donde estaba
                return homeStore.dropOnInstance(p.key, p.instanceId, occ.id);
            }
        }
        return p.instanceId == null
                // desde el cajón: instancia NUEVA (aunque la app ya esté en la Home)
                ? homeStore.addNew(p.key, t.dock, t.page, t.row, t.col)
                // ya colocada: se MUEVE (no se crea otra)
                : homeStore.move(p.instanceId, t.dock, t.page, t.row, t.col);
    }

    private void beginFolderDrag(View v, String folderId) {
        dismissMenu();
        ClipData clip = ClipData.newPlainText("folder", folderId);
        boolean started = v.startDragAndDrop(clip, new View.DragShadowBuilder(v),
                new DragPayload(null, null, folderId), 0);
        if (started) v.setAlpha(0.35f);
    }

    /** Menú de la carpeta cerrada en la Home. */
    private void showFolderMenu(View anchor, String folderId) {
        LinearLayout col = newMenuColumn();
        col.addView(menuRow(getString(R.string.cambiar_de_nombre), () -> showFolderRenameSheet(folderId)));
        col.addView(menuRow(getString(R.string.eliminar_carpeta), () -> deleteFolder(folderId)));
        popup(anchor, col);
    }

    /** Elimina la carpeta y devuelve sus apps a la Home (en lugares libres cercanos). */
    private void deleteFolder(String folderId) {
        int lost = homeStore.deleteFolder(folderId);
        if (folderId.equals(openFolderId)) dismissFolderPanel();
        renderHome();
        toast(lost > 0 ? R.string.apps_no_caben : R.string.carpeta_eliminada);
    }

    // ---- Panel de la carpeta abierta ---------------------------------------------

    private void showFolderPanel(String folderId) {
        dismissFolderPanel();
        dismissMenu();
        HomeStore.Folder f = homeStore.folder(folderId);
        if (f == null) return;

        BottomSheet s = new BottomSheet(this, folderDisplayName(f), null);
        TextView title = s.title();
        title.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT; // el lápiz queda junto al nombre
        title.setPadding(0, dp(6), 0, dp(6));
        title.setCompoundDrawablePadding(dp(10));
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_pencil, 0);
        title.setClickable(true);
        title.setOnClickListener(v -> showFolderRenameSheet(folderId)); // tocar el nombre lo edita
        s.setOnDismissListener(() -> {
            if (folderSheet == s) {
                folderSheet = null;
                openFolderId = null;
            }
        });
        folderSheet = s;
        openFolderId = folderId;
        fillFolderBody();
        s.show();
    }

    /** (Re)dibuja las apps de la carpeta abierta en un grid de 4 columnas. Si la carpeta ya no existe, cierra el panel. */
    private void fillFolderBody() {
        if (folderSheet == null || openFolderId == null) return;
        HomeStore.Folder f = homeStore.folder(openFolderId);
        if (f == null) {
            dismissFolderPanel();
            return;
        }
        folderSheet.title().setText(folderDisplayName(f));
        LinearLayout body = folderSheet.body();
        body.removeAllViews();

        LinearLayout row = null;
        int col = 0;
        for (HomeStore.Member m : f.members) {
            AppEntry app = allApps.get(m.key);
            if (app == null) continue; // desinstalada o aún cargando: se salta, sin errores
            if (col == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(makeFolderMemberView(f.id, m, app, row),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col = (col + 1) % 4;
        }
        // completa la última fila con huecos para que todas las celdas midan lo mismo
        while (col != 0 && row != null) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            col = (col + 1) % 4;
        }
        FontManager.apply(body);
    }

    private View makeFolderMemberView(String folderId, HomeStore.Member m, AppEntry app, ViewGroup parent) {
        View item = getLayoutInflater().inflate(R.layout.item_app, parent, false);
        ImageView icon = item.findViewById(R.id.icon);
        TextView label = item.findViewById(R.id.label);
        icon.setImageDrawable(app.newIcon(getResources()));
        label.setText(app.label());
        item.setOnClickListener(v -> {
            dismissFolderPanel();
            openApp(app);
        });
        item.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showFolderMemberMenu(v, folderId, m, app);
            return true;
        });
        return item;
    }

    /** Menú de una app dentro de la carpeta (igual que el de la Home, con "Quitar de la carpeta"). */
    private void showFolderMemberMenu(View anchor, String folderId, HomeStore.Member m, AppEntry app) {
        LinearLayout col = newMenuColumn();
        col.addView(menuRow(getString(R.string.abrir), () -> {
            dismissFolderPanel();
            openApp(app);
        }));
        col.addView(menuRow(getString(R.string.info_app), () -> openInfo(app)));
        col.addView(menuRow(getString(R.string.cambiar_icono), () -> pickIcon(app)));
        col.addView(menuRow(getString(R.string.cambiar_nombre), () -> showRenameSheet(app)));
        col.addView(menuRow(getString(R.string.quitar_de_carpeta), () -> removeFromFolder(folderId, m)));
        popup(anchor, col);
    }

    /** Saca la app de la carpeta y la deja en la Home. Si la carpeta queda vacía, desaparece. */
    private void removeFromFolder(String folderId, HomeStore.Member m) {
        if (homeStore.removeMember(folderId, m.id) == HomeStore.FULL) {
            toast(R.string.home_llena);
            return;
        }
        renderHome(); // también actualiza (o cierra) el panel
    }

    private void showFolderRenameSheet(String folderId) {
        HomeStore.Folder f = homeStore.folder(folderId);
        if (f == null) return;
        dismissSheet();
        BottomSheet s = new BottomSheet(this, getString(R.string.cambiar_de_nombre), null);

        EditText et = new EditText(this);
        et.setText(folderDisplayName(f));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        et.setTextColor(getColor(R.color.ink));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setHighlightColor(getColor(R.color.gold_bg));
        et.setBackgroundResource(R.drawable.input_bg);
        et.setMinHeight(dp(44));
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        s.body().addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable save = () -> {
            String t = et.getText().toString().trim();
            // Vacío o igual al nombre por defecto = volver a "Carpeta"
            homeStore.renameFolder(folderId, (t.isEmpty() || t.equals(getString(R.string.carpeta))) ? "" : t);
            s.dismiss();
            renderHome(); // el nombre nuevo se ve debajo de la carpeta y en el panel
        };

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView ok = BottomSheet.button(this, getString(R.string.guardar), true);
        TextView cancel = BottomSheet.button(this, getString(R.string.cancelar), false);
        ok.setOnClickListener(v -> save.run());
        cancel.setOnClickListener(v -> s.dismiss());
        buttons.addView(ok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(10);
        buttons.addView(cancel, cp);
        s.footer().addView(buttons);

        if (!f.name.isEmpty()) {
            TextView restore = BottomSheet.button(this, getString(R.string.restaurar_nombre), false);
            restore.setOnClickListener(v -> {
                homeStore.renameFolder(folderId, "");
                s.dismiss();
                renderHome();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(10);
            s.footer().addView(restore, rp);
        }

        et.setOnEditorActionListener((v, actionId, ev) -> {
            save.run();
            return true;
        });

        sheet = s;
        s.keyboard();
        s.show();
        et.requestFocus();
    }

    private void dismissFolderPanel() {
        if (folderSheet != null) {
            BottomSheet s = folderSheet;
            folderSheet = null;
            openFolderId = null;
            s.dismiss();
        }
    }

    // =========================================================================
    //  Acciones básicas
    // =========================================================================

    private void openApp(AppEntry app) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(R.string.no_se_pudo_abrir);
        }
    }

    private void openInfo(AppEntry app) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null));
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(R.string.no_se_pudo_abrir);
        }
    }

    // =========================================================================
    //  HOME y DOCK
    // =========================================================================

    private void buildDock() {
        dock.removeAllViews();
        for (int i = 0; i < HomeStore.DOCK_SLOTS; i++) {
            FrameLayout slot = new FrameLayout(this);
            slot.setBackgroundResource(R.drawable.cell_bg);
            dockSlots[i] = slot;
            dock.addView(slot, new LinearLayout.LayoutParams(0, dp(76), 1f));
        }

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.ln2));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(40));
        divLp.setMargins(dp(4), 0, dp(4), 0);
        dock.addView(divider, divLp);

        // Botón fijo que abre el cajón de apps (siempre al final del dock)
        FrameLayout btnSlot = new FrameLayout(this);
        ImageView btn = new ImageView(this);
        btn.setImageResource(R.drawable.ic_drawer_btn);
        btn.setBackgroundResource(R.drawable.item_bg);
        btn.setContentDescription(getString(R.string.abrir_cajon));
        btn.setOnClickListener(v -> openDrawer());
        btnSlot.addView(btn, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        dock.addView(btnSlot, new LinearLayout.LayoutParams(0, dp(76), 1f));
    }

    // ---- Páginas de la Home ---------------------------------------------------

    private void buildPager() {
        pagesAdapter = new PagesAdapter();
        pager.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        pager.setAdapter(pagesAdapter);
        new PagerSnapHelper().attachToRecyclerView(pager);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { syncCurrentFromScroll(); }
            @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) syncCurrentFromScroll();
            }
        });
        indicator.setOnSelect(i -> goToPage(i, true));
        currentIndex = Math.max(0, homeStore.mainIndex()); // al abrir: la página principal
        pager.scrollToPosition(currentIndex);
        syncIndicator();
    }

    private final class PagesAdapter extends RecyclerView.Adapter<PageVH> {
        @Override
        public PageVH onCreateViewHolder(ViewGroup parent, int viewType) {
            return newPage();
        }

        @Override
        public void onBindViewHolder(PageVH h, int position) {
            bindPage(h, homeStore.pageId(position));
        }

        @Override
        public int getItemCount() {
            return homeStore.pageCount();
        }
    }

    private PageVH newPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(24), dp(16), dp(24), dp(PAGE_BOTTOM_PAD_DP));
        PageVH h = new PageVH(root);
        for (int r = 0; r < HomeStore.HOME_ROWS; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < HomeStore.HOME_COLS; c++) {
                FrameLayout cell = new FrameLayout(this);
                cell.setBackgroundResource(R.drawable.cell_bg);
                h.cells[r][c] = cell;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(cell, lp);
            }
        }
        // Pulsación larga en un espacio vacío (los íconos atienden la suya) -> modo edición
        root.setLongClickable(true);
        root.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            enterEditMode();
            return true;
        });
        return h;
    }

    private void bindPage(PageVH h, int pageId) {
        for (FrameLayout[] row : h.cells) {
            for (FrameLayout cell : row) cell.removeAllViews();
        }
        // Se recorre la lista de instancias y se dibuja cada una en su posición.
        for (HomeStore.Instance inst : homeStore.snapshot()) {
            if (inst.dock || inst.page != pageId) continue;
            AppEntry app = allApps.get(inst.key);
            if (app == null) continue; // desinstalada o aún cargando: se salta, sin errores
            FrameLayout cell = h.cells[inst.row][inst.col];
            cell.addView(makeHomeItem(inst, app, cell));
        }
        for (HomeStore.Folder fo : homeStore.folders()) {
            if (fo.page != pageId) continue;
            FrameLayout cell = h.cells[fo.row][fo.col];
            cell.addView(makeFolderItem(fo, cell));
        }
    }

    /** Redibuja páginas, dock, indicador y mensaje desde lo guardado. */
    private void renderHome() {
        pagesAdapter.notifyDataSetChanged();
        for (FrameLayout slot : dockSlots) slot.removeAllViews();

        int shown = 0;
        for (HomeStore.Instance inst : homeStore.snapshot()) {
            AppEntry app = allApps.get(inst.key);
            if (app == null) continue;
            if (inst.dock) {
                dockSlots[inst.col].addView(makeDockItem(inst, app));
            } else {
                shown++;
            }
        }
        shown += homeStore.folders().size();
        syncIndicator();
        homeHint.setVisibility(loaded && shown == 0 ? View.VISIBLE : View.GONE);
        fillFolderBody(); // si hay una carpeta abierta, se actualiza (nombres, íconos, apps desinstaladas)
    }

    private void syncIndicator() {
        indicator.setPages(homeStore.pageCount(), homeStore.mainIndex(), currentIndex);
    }

    private void goToPage(int index, boolean smooth) {
        if (index < 0 || index >= homeStore.pageCount()) return;
        currentIndex = index;
        indicator.setCurrent(index);
        if (smooth) pager.smoothScrollToPosition(index); else pager.scrollToPosition(index);
    }

    private void syncCurrentFromScroll() {
        if (editMode) return;
        int idx = indexFromScroll(pager);
        if (idx >= 0 && idx != currentIndex) {
            currentIndex = idx;
            indicator.setCurrent(idx);
        }
    }

    /** Página más cercana al centro según el desplazamiento de un carrusel horizontal (con o sin relleno/márgenes). */
    private int indexFromScroll(RecyclerView rv) {
        RecyclerView.LayoutManager m = rv.getLayoutManager();
        if (!(m instanceof LinearLayoutManager)) return -1;
        LinearLayoutManager lm = (LinearLayoutManager) m;
        int first = lm.findFirstVisibleItemPosition();
        if (first < 0) return -1;
        View v = lm.findViewByPosition(first);
        if (v == null || v.getWidth() == 0) return -1;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        int ml = 0;
        int mr = 0;
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ml = ((ViewGroup.MarginLayoutParams) raw).leftMargin;
            mr = ((ViewGroup.MarginLayoutParams) raw).rightMargin;
        }
        float pos = first + (rv.getPaddingLeft() + ml - v.getLeft()) / (float) (v.getWidth() + ml + mr);
        return Math.max(0, Math.min(homeStore.pageCount() - 1, Math.round(pos)));
    }

    private PageVH currentPageHolder() {
        RecyclerView.ViewHolder vh = pager.findViewHolderForAdapterPosition(currentIndex);
        return vh instanceof PageVH ? (PageVH) vh : null;
    }

    private View makeHomeItem(HomeStore.Instance inst, AppEntry app, FrameLayout cell) {
        View item = getLayoutInflater().inflate(R.layout.item_app, cell, false);
        ((FrameLayout.LayoutParams) item.getLayoutParams()).gravity = Gravity.CENTER;
        ImageView icon = item.findViewById(R.id.icon);
        TextView label = item.findViewById(R.id.label);
        icon.setImageDrawable(app.newIcon(getResources()));
        label.setText(app.label());
        label.setShadowLayer(dp(3), 0, dp(1), 0xCC000000); // legible sobre cualquier fondo
        FontManager.applyTo(label);
        item.setTag(new Placed(app, inst.id));              // esta INSTANCIA, no solo la app
        item.setOnClickListener(v -> openApp(app));         // todas las instancias abren la misma app
        placedDrag.attach(item);
        return item;
    }

    private View makeDockItem(HomeStore.Instance inst, AppEntry app) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(app.newIcon(getResources()));
        iv.setBackgroundResource(R.drawable.item_bg);
        iv.setContentDescription(app.label());
        iv.setLayoutParams(new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        iv.setTag(new Placed(app, inst.id));
        iv.setOnClickListener(v -> openApp(app));
        placedDrag.attach(iv);
        return iv;
    }

    /**
     * Menú de un ícono de la Home o del dock.
     * Cambiar ícono / nombre / Restaurar afectan a la APP (todas sus instancias y el cajón).
     * "Quitar" afecta solo a esta instancia.
     */
    private void showPlacedMenu(View anchor, Placed pl) {
        AppEntry app = pl.app;
        HomeStore.Instance inst = homeStore.get(pl.instanceId);
        boolean inDock = inst != null && inst.dock;
        LinearLayout col = newMenuColumn();
        col.addView(menuRow(getString(R.string.abrir), () -> openApp(app)));
        col.addView(menuRow(getString(R.string.info_app), () -> openInfo(app)));
        col.addView(menuRow(getString(R.string.cambiar_icono), () -> pickIcon(app)));
        col.addView(menuRow(getString(R.string.cambiar_nombre), () -> showRenameSheet(app)));
        if (app.hasCustomization()) {
            col.addView(menuRow(getString(R.string.restaurar), () -> restoreAll(app)));
        }
        col.addView(menuRow(getString(inDock ? R.string.quitar_dock : R.string.quitar_home), () -> {
            homeStore.remove(pl.instanceId);
            renderHome();
        }));
        popup(anchor, col);
    }

    // =========================================================================
    //  Cajón de apps: abrir / cerrar
    // =========================================================================

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        syncVideoPlayback(); // el video de la Home se pausa y corre el del cajón
        dismissMenu();
        dismissSheet();

        int screenH = getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) drawerSheet.getLayoutParams();
        lp.topMargin = Math.round(screenH * 0.10f);
        drawerSheet.setLayoutParams(lp);
        grid.scrollToPosition(0);

        drawerSheet.animate().cancel();
        drawerScrim.animate().cancel();
        drawerLayer.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha(0f);
        drawerSheet.setTranslationY(screenH);
        drawerScrim.animate().alpha(1f).setDuration(240).start();
        drawerSheet.animate().translationY(0f).setDuration(340)
                .setInterpolator(new DecelerateInterpolator(2.5f)).start();
    }

    private void closeDrawer(boolean animate, boolean fast) {
        if (!drawerOpen) return;
        drawerOpen = false;
        syncVideoPlayback(); // el video del cajón se pausa y vuelve a correr el de la Home
        dismissMenu();
        drawerSheet.animate().cancel();
        drawerScrim.animate().cancel();
        if (!animate) {
            drawerLayer.setVisibility(View.GONE);
            return;
        }
        int d = fast ? 180 : 240;
        drawerScrim.animate().alpha(0f).setDuration(d).start();
        drawerSheet.animate().translationY(drawerLayer.getHeight()).setDuration(d)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    if (!drawerOpen) drawerLayer.setVisibility(View.GONE);
                }).start();
    }

    // =========================================================================
    //  Arrastrar y soltar (cajón -> Home/dock, y mover dentro de Home/dock)
    // =========================================================================

    /** instanceId == null: viene del cajón (se creará una instancia nueva). Si no: se mueve esa instancia. */
    private void beginDrag(View v, String key, String instanceId) {
        dismissMenu();
        ClipData clip = ClipData.newPlainText("app", key);
        boolean started = v.startDragAndDrop(clip, new View.DragShadowBuilder(v),
                new DragPayload(key, instanceId, null), 0);
        if (!started) return;
        if (instanceId == null) {
            closeDrawer(true, true);                 // el cajón baja para dejar ver la Home
        } else {
            v.setAlpha(0.35f);                       // el ícono "levantado"
            homeTrash.setVisibility(View.VISIBLE);   // se puede soltar en la papelera para quitarlo
        }
    }

    private boolean onHomeDrag(View v, DragEvent e) {
        Object ls = e.getLocalState();
        if (!(ls instanceof DragPayload)) return false;
        DragPayload payload = (DragPayload) ls;

        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION: {
                View over = null;
                if (overTrash(e.getX(), e.getY(), payload)) {
                    over = homeTrash;
                } else {
                    Target t = targetAt(e.getX(), e.getY());
                    if (t != null) over = t.view;
                }
                setHot(over);
                // junto al borde: pasa a la página vecina para poder soltar allí
                setEdgeDir(e.getX() < dp(48) ? -1 : (e.getX() > v.getWidth() - dp(48) ? 1 : 0));
                return true;
            }
            case DragEvent.ACTION_DRAG_EXITED:
                setHot(null);
                setEdgeDir(0);
                return true;
            case DragEvent.ACTION_DROP: {
                boolean onTrash = overTrash(e.getX(), e.getY(), payload);
                Target t = onTrash ? null : targetAt(e.getX(), e.getY());
                setHot(null);
                setEdgeDir(0);
                if (onTrash) {
                    homeStore.remove(payload.instanceId); // quita solo esta instancia
                    return true;
                }
                if (t == null) return false; // soltado fuera de la cuadrícula y del dock: no pasa nada
                int res = applyDrop(payload, t);
                if (res == HomeStore.FULL) toast(t.dock ? R.string.dock_lleno : R.string.home_llena);
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                setHot(null);
                setEdgeDir(0);
                homeTrash.setVisibility(View.INVISIBLE);
                ui.post(this::renderHome); // también restaura el ícono "levantado" si no se movió
                return true;
            default:
                return false;
        }
    }

    private boolean overTrash(float x, float y, DragPayload p) {
        if (p.instanceId == null || homeTrash.getVisibility() != View.VISIBLE) return false;
        return x >= homeTrash.getLeft() - dp(20) && x <= homeTrash.getRight() + dp(20)
                && y >= homeTrash.getTop() - dp(20) && y <= homeTrash.getBottom() + dp(20);
    }

    private void setEdgeDir(int dir) {
        if (dir == edgeDir) return;
        edgeDir = dir;
        ui.removeCallbacks(edgeTask);
        if (dir != 0) ui.postDelayed(edgeTask, 650);
    }

    private void edgeTick() {
        if (edgeDir == 0) return;
        int target = currentIndex + edgeDir;
        if (target < 0 || target >= homeStore.pageCount()) return;
        setHot(null);
        goToPage(target, true);
        ui.postDelayed(edgeTask, 900);
    }

    /** ¿Qué espacio del dock o celda de la página actual hay bajo el dedo? (coordenadas de la capa Home) */
    private Target targetAt(float x, float y) {
        if (y >= dock.getTop() - dp(16) && y <= dock.getBottom() + dp(16)
                && x >= dock.getLeft() && x <= dock.getRight()) {
            int best = 0;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < HomeStore.DOCK_SLOTS; i++) {
                float cx = dock.getLeft() + dockSlots[i].getLeft() + dockSlots[i].getWidth() / 2f;
                float d = Math.abs(x - cx);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return new Target(true, 0, 0, best, dockSlots[best]);
        }

        PageVH h = currentPageHolder();
        if (h == null) return null;
        int[] a = new int[2];
        int[] b = new int[2];
        homeLayer.getLocationOnScreen(a);
        h.root.getLocationOnScreen(b);
        float left = b[0] - a[0] + h.root.getPaddingLeft();
        float top = b[1] - a[1] + h.root.getPaddingTop();
        float w = h.root.getWidth() - h.root.getPaddingLeft() - h.root.getPaddingRight();
        float hh = h.root.getHeight() - h.root.getPaddingTop() - h.root.getPaddingBottom();
        if (w <= 0 || hh <= 0 || x < left || x > left + w || y < top || y > top + hh) return null;
        int c = Math.min(HomeStore.HOME_COLS - 1, (int) ((x - left) / (w / HomeStore.HOME_COLS)));
        int r = Math.min(HomeStore.HOME_ROWS - 1, (int) ((y - top) / (hh / HomeStore.HOME_ROWS)));
        return new Target(false, homeStore.pageId(currentIndex), r, c, h.cells[r][c]);
    }

    private void setHot(View v) {
        if (hot == v) return;
        if (hot != null) hot.setActivated(false);
        hot = v;
        if (v != null) v.setActivated(true);
    }

    // =========================================================================
    //  Modo edición de la Home: páginas como tarjetas (mantener pulsado un espacio vacío)
    // =========================================================================

    private void buildEditMode() {
        editLayer = findViewById(R.id.edit_layer);
        editPager = findViewById(R.id.edit_pager);
        editIndicator = findViewById(R.id.edit_indicator);
        editTrash = findViewById(R.id.edit_trash);
        editHint = findViewById(R.id.edit_hint);

        editDrag = new LongPressDrag(this, new LongPressDrag.Listener() {
            @Override public void onLongPress(View v) { }
            @Override public void onDragStart(View v) { startPageDrag(v); }
        });
        editAdapter = new EditAdapter();
        editPager.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        editPager.setAdapter(editAdapter);
        new PagerSnapHelper().attachToRecyclerView(editPager);
        editPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { syncEditFromScroll(); }
            @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) syncEditFromScroll();
            }
        });
        editIndicator.setOnSelect(i -> editPager.smoothScrollToPosition(i));

        findViewById(R.id.edit_done).setOnClickListener(v -> exitEditMode(true));
        findViewById(R.id.edit_add_left).setOnClickListener(v -> addPageAt(currentIndex));
        findViewById(R.id.edit_add_right).setOnClickListener(v -> addPageAt(currentIndex + 1));
        findViewById(R.id.edit_action_themes).setOnClickListener(v ->
                startActivity(new Intent(this, BackgroundsActivity.class)));
        findViewById(R.id.edit_action_widgets).setOnClickListener(v -> openSoon(R.string.widgets));
        findViewById(R.id.edit_action_config).setOnClickListener(v ->
                startActivity(new Intent(this, HomeSettingsActivity.class)));
        editLayer.setOnDragListener(this::onEditDrag);
    }

    private void openSoon(int titleRes) {
        startActivity(new Intent(this, ComingSoonActivity.class)
                .putExtra(ComingSoonActivity.EXTRA_TITLE, getString(titleRes)));
    }

    private void enterEditMode() {
        if (editMode || drawerOpen) return;
        editMode = true;
        syncAudio(); // un menú sobre la Home: el audio se pausa
        dismissMenu();
        dismissSheet();

        int screenW = getResources().getDisplayMetrics().widthPixels;
        editCardW = Math.round(screenW * 0.82f);
        int pad = Math.max(0, (screenW - editCardW - dp(16)) / 2); // centra la tarjeta; las vecinas asoman
        editPager.setPadding(pad, 0, pad, 0);
        editAdapter.notifyDataSetChanged();
        editPager.scrollToPosition(currentIndex);
        syncEditIndicator();

        homeLayer.animate().cancel();
        editLayer.animate().cancel();
        editLayer.setAlpha(0f);
        editLayer.setScaleX(1.05f);
        editLayer.setScaleY(1.05f);
        editLayer.setVisibility(View.VISIBLE);
        editLayer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        homeLayer.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> {
                    if (editMode) homeLayer.setVisibility(View.INVISIBLE);
                }).start();
    }

    private void exitEditMode(boolean animate) {
        if (!editMode) return;
        editMode = false;
        syncAudio();
        setEditEdge(0);
        setEditHot(null);
        homeLayer.animate().cancel();
        editLayer.animate().cancel();
        homeLayer.setVisibility(View.VISIBLE);
        pager.scrollToPosition(currentIndex);
        syncIndicator();
        if (!animate) {
            homeLayer.setAlpha(1f);
            editLayer.setVisibility(View.GONE);
            return;
        }
        homeLayer.setAlpha(0f);
        homeLayer.animate().alpha(1f).setDuration(220).start();
        editLayer.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> {
                    if (!editMode) editLayer.setVisibility(View.GONE);
                }).start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (editMode) exitEditMode(false); // las tarjetas se dimensionan al entrar
    }

    private void syncEditIndicator() {
        editIndicator.setPages(homeStore.pageCount(), homeStore.mainIndex(), currentIndex);
    }

    private void syncEditFromScroll() {
        if (!editMode) return;
        int idx = indexFromScroll(editPager);
        if (idx >= 0 && idx != currentIndex) {
            currentIndex = idx;
            editIndicator.setCurrent(idx);
        }
    }

    private void fillThumb(PageThumb thumb, int pageId) {
        List<Drawable> icons = new ArrayList<>();
        List<int[]> cells = new ArrayList<>();
        for (HomeStore.Instance inst : homeStore.snapshot()) {
            if (inst.dock || inst.page != pageId) continue;
            AppEntry app = allApps.get(inst.key);
            if (app == null) continue;
            icons.add(app.newIcon(getResources()));
            cells.add(new int[]{inst.row, inst.col});
        }
        for (HomeStore.Folder fo : homeStore.folders()) {
            if (fo.page != pageId) continue;
            icons.add(getDrawable(R.drawable.ic_folder));
            cells.add(new int[]{fo.row, fo.col});
        }
        thumb.setItems(icons, cells);
    }

    private final class EditAdapter extends RecyclerView.Adapter<CardVH> {
        @Override
        public CardVH onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout card = new FrameLayout(MainActivity.this);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    editCardW, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(dp(8), 0, dp(8), 0);
            card.setLayoutParams(lp);
            card.setBackgroundResource(R.drawable.page_card_bg);

            PageThumb thumb = new PageThumb(MainActivity.this);
            card.addView(thumb, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView house = new ImageView(MainActivity.this);
            house.setImageResource(R.drawable.ic_home);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                    dp(30), dp(30), Gravity.TOP | Gravity.START);
            hp.setMargins(dp(14), dp(14), 0, 0);
            card.addView(house, hp);

            editDrag.attach(card); // pulsación larga + mover = arrastrar la página
            return new CardVH(card, thumb, house);
        }

        @Override
        public void onBindViewHolder(CardVH h, int position) {
            int id = homeStore.pageId(position);
            ViewGroup.LayoutParams lp = h.card.getLayoutParams();
            if (lp.width != editCardW) {
                lp.width = editCardW;
                h.card.setLayoutParams(lp);
            }
            h.card.setTag(id);
            h.card.setAlpha(1f);
            h.card.setActivated(false);
            h.house.setVisibility(id == homeStore.mainId() ? View.VISIBLE : View.GONE);
            fillThumb(h.thumb, id);
            h.card.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) editPager.smoothScrollToPosition(p);
            });
        }

        @Override
        public int getItemCount() {
            return homeStore.pageCount();
        }
    }

    // ---- Añadir / eliminar / reordenar páginas ------------------------------

    private void afterPagesChanged() {
        renderHome();
        pager.scrollToPosition(currentIndex);
        editAdapter.notifyDataSetChanged();
        editPager.scrollToPosition(currentIndex);
        syncEditIndicator();
    }

    /** "+" izquierdo: index = actual (queda a su izquierda). "+" derecho: index = actual + 1. */
    private void addPageAt(int index) {
        int id = homeStore.insertPage(index);
        if (id < 0) {
            toast(R.string.max_paginas);
            return;
        }
        currentIndex = homeStore.indexOf(id); // la nueva página, vacía, pasa a ser la actual
        afterPagesChanged();
    }

    private boolean canDeletePage(int id) {
        return homeStore.pageCount() > 1 && id != homeStore.mainId();
    }

    private void tryDeletePage(int id) {
        if (id == homeStore.mainId()) {
            toast(R.string.pagina_principal_no);
            return;
        }
        if (homeStore.pageCount() <= 1) {
            toast(R.string.pagina_unica_no);
            return;
        }
        int curId = homeStore.pageId(currentIndex);
        if (!homeStore.removePage(id)) return;
        currentIndex = (id == curId) ? Math.max(0, homeStore.mainIndex()) : homeStore.indexOf(curId);
        afterPagesChanged();
        toast(R.string.pagina_eliminada);
    }

    private void reorderPage(int srcId, int dstId) {
        int from = homeStore.indexOf(srcId);
        int to = homeStore.indexOf(dstId);
        if (from < 0 || to < 0 || from == to) return;
        int curId = homeStore.pageId(currentIndex);
        homeStore.movePage(from, to);
        currentIndex = homeStore.indexOf(curId);
        afterPagesChanged();
    }

    // ---- Arrastre de tarjetas ------------------------------------------------

    private void startPageDrag(View v) {
        Object tag = v.getTag();
        if (!(tag instanceof Integer)) return;
        int id = (Integer) tag;
        boolean started = v.startDragAndDrop(ClipData.newPlainText("page", String.valueOf(id)),
                new View.DragShadowBuilder(v), new PageDragPayload(id), 0);
        if (!started) return;
        v.setAlpha(0.35f);
        editTrash.setAlpha(canDeletePage(id) ? 1f : 0.35f); // principal / única: papelera atenuada
        editTrash.setVisibility(View.VISIBLE);
        editHint.setVisibility(View.INVISIBLE);
    }

    private boolean onEditDrag(View root, DragEvent e) {
        Object ls = e.getLocalState();
        if (!(ls instanceof PageDragPayload)) return false;
        int id = ((PageDragPayload) ls).id;

        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                setEditHot(editTargetAt(root, e.getX(), e.getY()));
                setEditEdge(e.getX() < dp(56) ? -1 : (e.getX() > root.getWidth() - dp(56) ? 1 : 0));
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                setEditHot(null);
                setEditEdge(0);
                return true;
            case DragEvent.ACTION_DROP: {
                View t = editTargetAt(root, e.getX(), e.getY());
                setEditHot(null);
                setEditEdge(0);
                if (t == editTrash) {
                    tryDeletePage(id);                 // sobre la papelera: eliminar
                } else if (t != null && t.getTag() instanceof Integer) {
                    reorderPage(id, (Integer) t.getTag()); // sobre otra tarjeta: reordenar
                }
                return true;                           // en otro lugar: no pasa nada
            }
            case DragEvent.ACTION_DRAG_ENDED:
                setEditHot(null);
                setEditEdge(0);
                editTrash.setVisibility(View.INVISIBLE);
                editHint.setVisibility(View.VISIBLE);
                ui.post(() -> {
                    if (!editMode) return;
                    editAdapter.notifyDataSetChanged();   // la tarjeta vuelve a su lugar
                    editPager.smoothScrollToPosition(currentIndex);
                });
                return true;
            default:
                return false;
        }
    }

    private View editTargetAt(View root, float x, float y) {
        if (editTrash.getVisibility() == View.VISIBLE && hitOn(editTrash, root, x, y, dp(20))) {
            return editTrash;
        }
        for (int i = 0; i < editPager.getChildCount(); i++) {
            View c = editPager.getChildAt(i);
            if (hitOn(c, root, x, y, 0)) return c;
        }
        return null;
    }

    private boolean hitOn(View v, View root, float x, float y, int slack) {
        int[] a = new int[2];
        int[] b = new int[2];
        root.getLocationOnScreen(a);
        v.getLocationOnScreen(b);
        float l = b[0] - a[0] - slack;
        float t = b[1] - a[1] - slack;
        return x >= l && x <= l + v.getWidth() + 2 * slack
                && y >= t && y <= t + v.getHeight() + 2 * slack;
    }

    private void setEditHot(View v) {
        if (editHot == v) return;
        if (editHot != null) editHot.setActivated(false);
        editHot = v;
        if (v != null) v.setActivated(true);
    }

    /** Arrastrando una tarjeta junto al borde, el carrusel se desplaza para alcanzar otras páginas. */
    private void setEditEdge(int dir) {
        if (dir == editEdgeDir) return;
        editEdgeDir = dir;
        ui.removeCallbacks(editEdgeTask);
        if (dir != 0) ui.post(editEdgeTask);
    }

    private void editEdgeTick() {
        if (editEdgeDir == 0 || !editMode) return;
        editPager.scrollBy(editEdgeDir * dp(14), 0);
        ui.postDelayed(editEdgeTask, 16);
    }

    // =========================================================================
    //  Cambiar ícono
    //  ACTION_OPEN_DOCUMENT: no requiere ningún permiso y deja conservar el acceso
    //  de lectura SOLO a la imagen elegida (takePersistableUriPermission).
    // =========================================================================

    private void pickIcon(AppEntry app) {
        pendingIconKey = app.key();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_ICON);
        } catch (ActivityNotFoundException e) {
            pendingIconKey = null;
            toast(R.string.selector_no_disponible);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ICON) return;

        final String key = pendingIconKey;
        pendingIconKey = null;
        // Cancelado: no se cambia nada.
        if (resultCode != RESULT_OK || data == null || data.getData() == null || key == null) return;

        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | UnsupportedOperationException e) {
            toast(R.string.imagen_sin_permiso);
            return;
        }

        final int iconPx = dp(64);
        final String uriStr = uri.toString();
        io.execute(() -> {
            Drawable d = IconLoader.load(this, uriStr, iconPx); // valida que sea una imagen legible
            ui.post(() -> {
                if (isDestroyed()) return;
                if (d == null) {
                    releaseIfUnused(uriStr);
                    toast(R.string.imagen_no_leida);
                    return;
                }
                String old = store.get(key).icon;
                store.setIcon(key, uriStr);
                if (old != null && !old.equals(uriStr)) releaseIfUnused(old);
                reload();
            });
        });
    }

    /** Suelta el permiso de lectura de una imagen cuando ninguna app la usa. */
    private void releaseIfUnused(String uriStr) {
        if (uriStr == null || store.isIconInUse(uriStr)) return;
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uriStr), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    // =========================================================================
    //  Restaurar / olvidar
    // =========================================================================

    private void restoreAll(AppEntry app) {
        String old = store.get(app.key()).icon;
        store.clear(app.key());
        releaseIfUnused(old);
        toast(R.string.app_restaurada);
        reload();
    }

    private void forget(String pkg) {
        if (pkg == null) return;
        for (String uri : store.clearPackage(pkg)) releaseIfUnused(uri);
        homeStore.removePackage(pkg); // desaparece de la Home y del dock sin errores
    }

    // =========================================================================
    //  Ocultar
    // =========================================================================

    private void hideApp(AppEntry app) {
        store.setHidden(app.key(), true);
        toast(R.string.app_oculta);
        reload();
    }

    private void showHiddenSheet() {
        dismissSheet();
        if (hiddenApps.isEmpty()) return;

        BottomSheet s = new BottomSheet(this, getString(R.string.apps_ocultas),
                getString(R.string.apps_ocultas_ayuda));
        for (AppEntry app : new ArrayList<>(hiddenApps)) {
            s.body().addView(hiddenRow(app, s));
        }
        TextView close = BottomSheet.button(this, getString(R.string.cerrar), false);
        close.setOnClickListener(v -> s.dismiss());
        s.footer().addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sheet = s;
        s.show();
    }

    private View hiddenRow(AppEntry app, BottomSheet s) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.newIcon(getResources()));
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView name = new TextView(this);
        name.setText(app.label());
        name.setTextColor(getColor(R.color.ink));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(12);
        np.rightMargin = dp(12);
        row.addView(name, np);

        TextView restore = BottomSheet.button(this, getString(R.string.restaurar), false);
        restore.setOnClickListener(v -> {
            store.setHidden(app.key(), false); // solo la vuelve a mostrar
            s.body().removeView(item);
            if (s.body().getChildCount() == 0) s.dismiss();
            reload();
        });
        row.addView(restore, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.ln));

        item.addView(row);
        item.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return item;
    }

    // =========================================================================
    //  Cambiar nombre
    // =========================================================================

    private void showRenameSheet(AppEntry app) {
        dismissSheet();
        BottomSheet s = new BottomSheet(this, getString(R.string.cambiar_nombre),
                getString(R.string.nombre_original, app.originalLabel));

        EditText et = new EditText(this);
        et.setText(app.label());
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        et.setTextColor(getColor(R.color.ink));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setHighlightColor(getColor(R.color.gold_bg));
        et.setBackgroundResource(R.drawable.input_bg);
        et.setMinHeight(dp(44));
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        s.body().addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable save = () -> {
            String t = et.getText().toString().trim();
            // Vacío o igual al original = volver al nombre original.
            store.setName(app.key(), (t.isEmpty() || t.equals(app.originalLabel)) ? null : t);
            s.dismiss();
            reload();
        };

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView ok = BottomSheet.button(this, getString(R.string.guardar), true);
        TextView cancel = BottomSheet.button(this, getString(R.string.cancelar), false);
        ok.setOnClickListener(v -> save.run());
        cancel.setOnClickListener(v -> s.dismiss());
        buttons.addView(ok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(10);
        buttons.addView(cancel, cp);
        s.footer().addView(buttons);

        if (app.customLabel != null) {
            TextView restore = BottomSheet.button(this, getString(R.string.restaurar_nombre), false);
            restore.setOnClickListener(v -> {
                store.setName(app.key(), null);
                s.dismiss();
                reload();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(10);
            s.footer().addView(restore, rp);
        }

        et.setOnEditorActionListener((v, actionId, ev) -> {
            save.run();
            return true;
        });

        sheet = s;
        s.keyboard();
        s.show();
        et.requestFocus();
    }

    // =========================================================================
    //  Menú de pulsación larga
    // =========================================================================

    /** Menú del cajón (sin cambios: Abrir, Información, Cambiar ícono, Cambiar nombre, Ocultar, Restaurar). */
    private void showMenu(View anchor, AppEntry app) {
        LinearLayout col = newMenuColumn();
        col.addView(menuRow(getString(R.string.abrir), () -> {
            openApp(app);
            closeDrawer(false, false);
        }));
        col.addView(menuRow(getString(R.string.info_app), () -> openInfo(app)));
        col.addView(menuRow(getString(R.string.cambiar_icono), () -> pickIcon(app)));
        col.addView(menuRow(getString(R.string.cambiar_nombre), () -> showRenameSheet(app)));
        col.addView(menuRow(getString(R.string.ocultar_app), () -> hideApp(app)));
        if (app.hasCustomization()) {
            col.addView(menuRow(getString(R.string.restaurar), () -> restoreAll(app)));
        }
        popup(anchor, col);
    }

    private LinearLayout newMenuColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundResource(R.drawable.menu_bg);
        col.setPadding(dp(6), dp(6), dp(6), dp(6));
        return col;
    }

    private void popup(View anchor, LinearLayout col) {
        dismissMenu();
        FontManager.apply(col);
        menu = new PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setElevation(dp(8));
        final PopupWindow self = menu;
        menu.setOnDismissListener(() -> {
            if (menu == self) menu = null; // no borrar un menú más nuevo
        });

        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int menuH = col.getMeasuredHeight();
        int xoff = (anchor.getWidth() - col.getMeasuredWidth()) / 2;

        // Debajo del ícono; si no cabe, arriba.
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int yoff = -anchor.getHeight() / 3;
        if (loc[1] + anchor.getHeight() + yoff + menuH > screenH - dp(24)) {
            yoff = -(anchor.getHeight() * 2 / 3) - menuH;
        }
        menu.showAsDropDown(anchor, xoff, yoff);
    }

    private TextView menuRow(String text, Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.ink));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(44));
        t.setMinWidth(dp(210));
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackgroundResource(R.drawable.row_bg);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            dismissMenu();
            action.run();
        });
        return t;
    }

    private void dismissMenu() {
        if (menu != null) {
            PopupWindow m = menu;
            menu = null;
            m.dismiss();
        }
    }

    private void dismissSheet() {
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
