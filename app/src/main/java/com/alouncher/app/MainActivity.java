package com.alouncher.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int COLUMNS = 4;
    private static final int REQ_PICK_ICON = 1001;
    private static final String STATE_PENDING_PKG = "pendingIconPkg";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    private CustomizationStore store;
    private RecyclerView grid;
    private AppAdapter adapter;
    private PopupWindow menu;
    private BottomSheet sheet;
    private BroadcastReceiver pkgReceiver;

    private List<AppEntry> hiddenApps = new ArrayList<>();
    private String pendingIconPkg; // app a la que se le está eligiendo ícono

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new CustomizationStore(this);
        if (savedInstanceState != null) {
            pendingIconPkg = savedInstanceState.getString(STATE_PENDING_PKG);
        }

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        adapter = new AppAdapter(new AppAdapter.Listener() {
            @Override public void onOpen(AppEntry app) { openApp(app); }
            @Override public void onMenu(View anchor, AppEntry app) { showMenu(anchor, app); }
            @Override public void onHiddenApps() { showHiddenSheet(); }
        });
        grid.setAdapter(adapter);

        // Mantiene la lista al día cuando instalas, actualizas o desinstalas algo.
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                // Desinstalación definitiva (no actualización): olvidar su personalización.
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
        outState.putString(STATE_PENDING_PKG, pendingIconPkg);
    }

    /** Se llama al pulsar HOME estando ya abierto (launchMode singleTask). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissMenu();
        dismissSheet();
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            grid.scrollToPosition(0);
        }
    }

    /** Un launcher no se cierra con "atrás". */
    @Override
    public void onBackPressed() {
        dismissMenu();
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

    // ---- Lista de apps + personalizaciones --------------------------------

    private void reload() {
        if (io.isShutdown()) return;
        final int iconPx = dp(64);
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);

            List<AppEntry> visible = new ArrayList<>();
            List<AppEntry> hidden = new ArrayList<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue; // no mostrarse a sí mismo

                Customization c = store.get(pkg);
                // Si la imagen ya no existe, load() devuelve null y se usa el ícono original.
                Drawable custom = c.icon != null ? IconLoader.load(this, c.icon, iconPx) : null;

                AppEntry e = new AppEntry(
                        pkg,
                        new ComponentName(pkg, ri.activityInfo.name),
                        ri.loadLabel(pm).toString(),
                        ri.loadIcon(pm),
                        c.name, c.icon, custom,
                        c.hidden);
                if (c.hidden) hidden.add(e); else visible.add(e);
            }
            Collator col = Collator.getInstance();
            visible.sort((a, b) -> col.compare(a.label(), b.label()));
            hidden.sort((a, b) -> col.compare(a.label(), b.label()));

            ui.post(() -> {
                if (isDestroyed()) return;
                hiddenApps = hidden;
                adapter.submit(visible, !hidden.isEmpty());
            });
        });
    }

    // ---- Acciones básicas -----------------------------------------------------

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

    // ---- Cambiar ícono -----------------------------------------------------------
    // ACTION_OPEN_DOCUMENT: no requiere ningún permiso y deja conservar el acceso
    // de lectura SOLO a la imagen elegida (takePersistableUriPermission).

    private void pickIcon(AppEntry app) {
        pendingIconPkg = app.packageName;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_ICON);
        } catch (ActivityNotFoundException e) {
            pendingIconPkg = null;
            toast(R.string.selector_no_disponible);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ICON) return;

        final String pkg = pendingIconPkg;
        pendingIconPkg = null;
        // Cancelado: no se cambia nada.
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pkg == null) return;

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
                String old = store.get(pkg).icon;
                store.setIcon(pkg, uriStr);
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

    // ---- Restaurar / olvidar --------------------------------------------------------

    private void restoreAll(AppEntry app) {
        String old = store.get(app.packageName).icon;
        store.clear(app.packageName);
        releaseIfUnused(old);
        toast(R.string.app_restaurada);
        reload();
    }

    private void forget(String pkg) {
        if (pkg == null) return;
        String old = store.get(pkg).icon;
        store.clear(pkg);
        releaseIfUnused(old);
    }

    // ---- Ocultar -----------------------------------------------------------------------

    private void hideApp(AppEntry app) {
        store.setHidden(app.packageName, true);
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
        icon.setImageDrawable(app.icon());
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
            store.setHidden(app.packageName, false); // solo la vuelve a mostrar
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

    // ---- Cambiar nombre -------------------------------------------------------------------

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
            store.setName(app.packageName, (t.isEmpty() || t.equals(app.originalLabel)) ? null : t);
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
                store.setName(app.packageName, null);
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

    // ---- Menú de pulsación larga ----------------------------------------------------------------

    private void showMenu(View anchor, AppEntry app) {
        dismissMenu();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundResource(R.drawable.menu_bg);
        col.setPadding(dp(6), dp(6), dp(6), dp(6));
        col.addView(menuRow(getString(R.string.abrir), () -> openApp(app)));
        col.addView(menuRow(getString(R.string.info_app), () -> openInfo(app)));
        col.addView(menuRow(getString(R.string.cambiar_icono), () -> pickIcon(app)));
        col.addView(menuRow(getString(R.string.cambiar_nombre), () -> showRenameSheet(app)));
        col.addView(menuRow(getString(R.string.ocultar_app), () -> hideApp(app)));
        if (app.hasCustomization()) {
            col.addView(menuRow(getString(R.string.restaurar), () -> restoreAll(app)));
        }

        menu = new PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setElevation(dp(8));

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
            menu.dismiss();
            menu = null;
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
