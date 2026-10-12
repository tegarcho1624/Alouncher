package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Qué app está en qué lugar de la Home y del dock. Se guarda en SharedPreferences
 * ("home_layout") como un JSON: [{"k":"paquete/actividad","d":true|ausente,"r":fila,"c":columna}].
 * En el dock "c" es el número de espacio (0..DOCK_SLOTS-1) y "r" siempre 0.
 */
final class HomeStore {
    static final int HOME_COLS = 6;
    static final int HOME_ROWS = 4;
    static final int DOCK_SLOTS = 7;

    static final int OK = 0;
    static final int FULL = 1;

    static final class Placement {
        final boolean dock;
        final int row;
        final int col;

        Placement(boolean dock, int row, int col) {
            this.dock = dock;
            this.row = row;
            this.col = col;
        }
    }

    private final SharedPreferences sp;
    private final LinkedHashMap<String, Placement> map = new LinkedHashMap<>();

    HomeStore(Context ctx) {
        sp = ctx.getSharedPreferences("home_layout", Context.MODE_PRIVATE);
        load();
    }

    private static boolean valid(boolean dock, int r, int c) {
        if (dock) return r == 0 && c >= 0 && c < DOCK_SLOTS;
        return r >= 0 && r < HOME_ROWS && c >= 0 && c < HOME_COLS;
    }

    private void load() {
        String raw = sp.getString("layout", null);
        if (raw == null) return;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String k = o.getString("k");
                boolean d = o.optBoolean("d", false);
                int r = o.optInt("r", 0);
                int c = o.optInt("c", 0);
                if (valid(d, r, c) && keyAt(d, r, c) == null) map.put(k, new Placement(d, r, c));
            }
        } catch (JSONException e) {
            map.clear();
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<String, Placement> e : map.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("k", e.getKey());
                if (e.getValue().dock) o.put("d", true);
                o.put("r", e.getValue().row);
                o.put("c", e.getValue().col);
                a.put(o);
            }
            sp.edit().putString("layout", a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    synchronized Map<String, Placement> snapshot() {
        return new LinkedHashMap<>(map);
    }

    synchronized Placement get(String key) {
        return map.get(key);
    }

    synchronized String keyAt(boolean dock, int r, int c) {
        for (Map.Entry<String, Placement> e : map.entrySet()) {
            Placement p = e.getValue();
            if (p.dock == dock && p.row == r && p.col == c) return e.getKey();
        }
        return null;
    }

    synchronized void remove(String key) {
        if (map.remove(key) != null) save();
    }

    /** Desinstalación: quita todo lo de ese paquete. */
    synchronized void removePackage(String pkg) {
        boolean changed = false;
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(pkg + "/")) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Al abrir el launcher: elimina lo que ya no está instalado para no dejar celdas "ocupadas" invisibles. */
    synchronized void pruneMissing(Set<String> installedKeys) {
        boolean changed = false;
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (!installedKeys.contains(it.next())) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Coloca (o mueve) una app en un lugar.
     *  - libre: se coloca.
     *  - ocupado y la app ya estaba colocada: intercambian lugares.
     *  - ocupado y la app viene del cajón: va al lugar libre más cercano (o FULL si no hay).
     */
    synchronized int drop(String key, boolean dock, int r, int c) {
        if (!valid(dock, r, c)) return FULL;
        Placement src = map.get(key);
        String occupant = keyAt(dock, r, c);

        if (key.equals(occupant)) return OK;

        if (occupant == null) {
            map.put(key, new Placement(dock, r, c));
            save();
            return OK;
        }
        if (src != null) {
            map.put(occupant, new Placement(src.dock, src.row, src.col));
            map.put(key, new Placement(dock, r, c));
            save();
            return OK;
        }
        Placement free = nearestFree(dock, r, c);
        if (free == null) return FULL;
        map.put(key, free);
        save();
        return OK;
    }

    private Placement nearestFree(boolean dock, int r0, int c0) {
        int rows = dock ? 1 : HOME_ROWS;
        int cols = dock ? DOCK_SLOTS : HOME_COLS;
        Placement best = null;
        int bestD = Integer.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (keyAt(dock, r, c) != null) continue;
                int d = (r - r0) * (r - r0) + (c - c0) * (c - c0);
                if (d < bestD) {
                    bestD = d;
                    best = new Placement(dock, r, c);
                }
            }
        }
        return best;
    }
}
