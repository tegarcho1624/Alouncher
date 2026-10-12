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
import androidx.recyclerview.widget.RecyclerView;

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

    // Home
    private LinearLayout homeGrid;
    private TextView homeHint;
    private CappedLinearLayout dock;
    private final FrameLayout[][] homeCells = new FrameLayout[HomeStore.HOME_ROWS][HomeStore.HOME_COLS];
    private final FrameLayout[] dockSlots = new FrameLayout[HomeStore.DOCK_SLOTS];
    private LongPressDrag placedDrag;
    private View hot; // celda o espacio resaltado durante un arrastre

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
    private boolean pruneOnNextLoad = true;
    private String pendingIconKey; // entrada (paquete/actividad) a la que se le elige ícono

    private static final class DragPayload {
        final String key;
        DragPayload(String key) { this.key = key; }
    }

    private static final class Target {
        final boolean dock;
        final int row;
        final int col;
        final View view;
        Target(boolean dock, int row, int col, View view) {
            this.dock = dock;
            this.row = row;
            this.col = col;
            this.view = view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new CustomizationStore(this);
        homeStore = new HomeStore(this);
        if (savedInstanceState != null) {
            pendingIconKey = savedInstanceState.getString(STATE_PENDING_KEY);
        }

        homeGrid = findViewById(R.id.home_grid);
        homeHint = findViewById(R.id.home_hint);
        dock = findViewById(R.id.dock);
        drawerLayer = findViewById(R.id.drawer_layer);
        drawerScrim = findViewById(R.id.drawer_scrim);
        drawerSheet = findViewById(R.id.drawer_sheet);
        grid = findViewById(R.id.grid);

        dock.setMaxWidth(dp(720));
        drawerSheet.setMaxWidth(dp(960));

        // ---- Home y dock
        placedDrag = new LongPressDrag(this, new LongPressDrag.Listener() {
            @Override public void onLongPress(View v) {
                Object t = v.getTag();
                if (t instanceof AppEntry) showPlacedMenu(v, (AppEntry) t);
            }
            @Override public void onDragStart(View v) {
                Object t = v.getTag();
                if (t instanceof AppEntry) beginDrag(v, ((AppEntry) t).key(), false);
            }
        });
        buildHomeGrid();
        buildDock();
        findViewById(R.id.home_layer).setOnDragListener(this::onHomeDrag);

        // ---- Cajón (misma lógica de siempre)
        grid.setLayoutManager(new GridLayoutManager(this, DRAWER_COLUMNS));
        adapter = new AppAdapter(new AppAdapter.Listener() {
            @Override public void onOpen(AppEntry app) {
                openApp(app);
                closeDrawer(false, false);
            }
            @Override public void onMenu(View anchor, AppEntry app) { showMenu(anchor, app); }
            @Override public void onDragStart(View anchor, AppEntry app) { beginDrag(anchor, app.key(), true); }
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

        reload();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_KEY, pendingIconKey);
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
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && drawerOpen) {
            closeDrawer(true, false);
        }
    }

    /** "Atrás": cierra el cajón si está abierto; en la Home no hace nada. */
    @Override
    public void onBackPressed() {
        if (menu != null) {
            dismissMenu();
        } else if (drawerOpen) {
            closeDrawer(true, false);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(reloadTask);
        dismissMenu();
        dismissSheet();
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        io.shutdownNow();
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

    private void buildHomeGrid() {
        homeGrid.removeAllViews();
        for (int r = 0; r < HomeStore.HOME_ROWS; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            homeGrid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < HomeStore.HOME_COLS; c++) {
                FrameLayout cell = new FrameLayout(this);
                cell.setBackgroundResource(R.drawable.cell_bg);
                homeCells[r][c] = cell;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(cell, lp);
            }
        }
    }

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

    /** Redibuja la Home y el dock desde lo guardado. Las apps que ya no existen simplemente no se dibujan. */
    private void renderHome() {
        for (FrameLayout[] row : homeCells) {
            for (FrameLayout cell : row) cell.removeAllViews();
        }
        for (FrameLayout slot : dockSlots) slot.removeAllViews();

        int shown = 0;
        for (Map.Entry<String, HomeStore.Placement> en : homeStore.snapshot().entrySet()) {
            AppEntry app = allApps.get(en.getKey());
            if (app == null) continue;
            HomeStore.Placement p = en.getValue();
            if (p.dock) {
                dockSlots[p.col].addView(makeDockItem(app));
            } else {
                FrameLayout cell = homeCells[p.row][p.col];
                cell.addView(makeHomeItem(app, cell));
                shown++;
            }
        }
        homeHint.setVisibility(loaded && shown == 0 ? View.VISIBLE : View.GONE);
    }

    private View makeHomeItem(AppEntry app, FrameLayout cell) {
        View item = getLayoutInflater().inflate(R.layout.item_app, cell, false);
        ((FrameLayout.LayoutParams) item.getLayoutParams()).gravity = Gravity.CENTER;
        ImageView icon = item.findViewById(R.id.icon);
        TextView label = item.findViewById(R.id.label);
        icon.setImageDrawable(app.newIcon(getResources()));
        label.setText(app.label());
        label.setShadowLayer(dp(3), 0, dp(1), 0xCC000000); // legible sobre cualquier fondo
        item.setTag(app);
        item.setOnClickListener(v -> openApp(app));
        placedDrag.attach(item);
        return item;
    }

    private View makeDockItem(AppEntry app) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(app.newIcon(getResources()));
        iv.setBackgroundResource(R.drawable.item_bg);
        iv.setContentDescription(app.label());
        iv.setLayoutParams(new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        iv.setTag(app);
        iv.setOnClickListener(v -> openApp(app));
        placedDrag.attach(iv);
        return iv;
    }

    private void showPlacedMenu(View anchor, AppEntry app) {
        HomeStore.Placement p = homeStore.get(app.key());
        boolean inDock = p != null && p.dock;
        LinearLayout col = newMenuColumn();
        col.addView(menuRow(getString(R.string.abrir), () -> openApp(app)));
        col.addView(menuRow(getString(R.string.info_app), () -> openInfo(app)));
        col.addView(menuRow(getString(inDock ? R.string.quitar_dock : R.string.quitar_home), () -> {
            homeStore.remove(app.key());
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

    private void beginDrag(View v, String key, boolean fromDrawer) {
        dismissMenu();
        ClipData clip = ClipData.newPlainText("app", key);
        boolean started = v.startDragAndDrop(clip, new View.DragShadowBuilder(v), new DragPayload(key), 0);
        if (!started) return;
        if (fromDrawer) {
            closeDrawer(true, true); // el cajón baja para dejar ver la Home
        } else {
            v.setAlpha(0.35f);       // el ícono "levantado"
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
                Target t = targetAt(e.getX(), e.getY());
                setHot(t != null ? t.view : null);
                return true;
            }
            case DragEvent.ACTION_DRAG_EXITED:
                setHot(null);
                return true;
            case DragEvent.ACTION_DROP: {
                Target t = targetAt(e.getX(), e.getY());
                setHot(null);
                if (t == null) return false; // soltado fuera de la cuadrícula y del dock: no pasa nada
                int res = homeStore.drop(payload.key, t.dock, t.row, t.col);
                if (res == HomeStore.FULL) toast(t.dock ? R.string.dock_lleno : R.string.home_llena);
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                setHot(null);
                ui.post(this::renderHome); // también restaura el ícono "levantado" si no se movió
                return true;
            default:
                return false;
        }
    }

    /** ¿Qué espacio del dock o celda de la Home hay bajo el dedo? (coordenadas de la capa Home) */
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
            return new Target(true, 0, best, dockSlots[best]);
        }

        float left = homeGrid.getLeft() + homeGrid.getPaddingLeft();
        float top = homeGrid.getTop() + homeGrid.getPaddingTop();
        float w = homeGrid.getWidth() - homeGrid.getPaddingLeft() - homeGrid.getPaddingRight();
        float h = homeGrid.getHeight() - homeGrid.getPaddingTop() - homeGrid.getPaddingBottom();
        if (w <= 0 || h <= 0 || x < left || x > left + w || y < top || y > top + h) return null;
        int c = Math.min(HomeStore.HOME_COLS - 1, (int) ((x - left) / (w / HomeStore.HOME_COLS)));
        int r = Math.min(HomeStore.HOME_ROWS - 1, (int) ((y - top) / (h / HomeStore.HOME_ROWS)));
        return new Target(false, r, c, homeCells[r][c]);
    }

    private void setHot(View v) {
        if (hot == v) return;
        if (hot != null) hot.setActivated(false);
        hot = v;
        if (v != null) v.setActivated(true);
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
