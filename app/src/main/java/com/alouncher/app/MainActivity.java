package com.alouncher.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    private RecyclerView grid;
    private AppAdapter adapter;
    private PopupWindow menu;
    private BroadcastReceiver pkgReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        adapter = new AppAdapter(new AppAdapter.Listener() {
            @Override public void onOpen(AppEntry app) { openApp(app); }
            @Override public void onMenu(View anchor, AppEntry app) { showMenu(anchor, app); }
        });
        grid.setAdapter(adapter);

        // Mantiene la lista al día cuando instalas, actualizas o desinstalas algo.
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                ui.removeCallbacks(reloadTask);
                ui.postDelayed(reloadTask, 400); // una actualización dispara quitar+agregar
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        reload();
    }

    /** Se llama al pulsar HOME estando ya abierto (launchMode singleTask). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissMenu();
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
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- Lista de apps -------------------------------------------------

    private void reload() {
        if (io.isShutdown()) return;
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);

            List<AppEntry> apps = new ArrayList<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue; // no mostrarse a sí mismo
                apps.add(new AppEntry(
                        ri.loadLabel(pm).toString(),
                        pkg,
                        new ComponentName(pkg, ri.activityInfo.name),
                        ri.loadIcon(pm)));
            }
            Collator col = Collator.getInstance();
            apps.sort((a, b) -> col.compare(a.label, b.label));

            ui.post(() -> {
                if (!isDestroyed()) adapter.submit(apps);
            });
        });
    }

    // ---- Acciones --------------------------------------------------------

    private void openApp(AppEntry app) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_se_pudo_abrir, Toast.LENGTH_SHORT).show();
        }
    }

    private void openInfo(AppEntry app) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null));
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_se_pudo_abrir, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Menú de pulsación larga ----------------------------------------

    private void showMenu(View anchor, AppEntry app) {
        dismissMenu();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundResource(R.drawable.menu_bg);
        col.setPadding(dp(6), dp(6), dp(6), dp(6));
        col.addView(menuRow(getString(R.string.abrir), () -> openApp(app)));
        col.addView(menuRow(getString(R.string.info_app), () -> openInfo(app)));

        menu = new PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setElevation(dp(8));

        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int xoff = (anchor.getWidth() - col.getMeasuredWidth()) / 2;
        menu.showAsDropDown(anchor, xoff, -anchor.getHeight() / 3);
    }

    private TextView menuRow(String text, Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.ink));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(44));
        t.setMinWidth(dp(200));
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
