package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Estado de la Home en SharedPreferences ("home_layout", clave "layout2"):
 * {"pages":[ids en orden], "main":idPrincipal, "next":siguienteId,
 *  "items":[{"k":"paquete/actividad","p":idPágina,"r":fila,"c":columna} | {"k":...,"d":true,"r":0,"c":espacio}]}
 * El dock es global (no pertenece a ninguna página). Si existe el formato anterior ("layout"),
 * se migra: todo lo que había en la Home pasa a la página 1, que es la principal.
 */
final class HomeStore {
    static final int HOME_COLS = 6;
    static final int HOME_ROWS = 4;
    static final int DOCK_SLOTS = 7;
    static final int MAX_PAGES = 7;

    static final int OK = 0;
    static final int FULL = 1;

    static final class Placement {
        final boolean dock;
        final int page;   // 0 en el dock
        final int row;
        final int col;

        Placement(boolean dock, int page, int row, int col) {
            this.dock = dock;
            this.page = page;
            this.row = row;
            this.col = col;
        }
    }

    private final SharedPreferences sp;
    private final LinkedHashMap<String, Placement> map = new LinkedHashMap<>();
    private final ArrayList<Integer> pages = new ArrayList<>();
    private int mainId = 1;
    private int nextId = 2;

    HomeStore(Context ctx) {
        sp = ctx.getSharedPreferences("home_layout", Context.MODE_PRIVATE);
        load();
    }

    private boolean valid(boolean dock, int page, int r, int c) {
        if (dock) return r == 0 && c >= 0 && c < DOCK_SLOTS;
        return pages.contains(page) && r >= 0 && r < HOME_ROWS && c >= 0 && c < HOME_COLS;
    }

    private void load() {
        try {
            String raw2 = sp.getString("layout2", null);
            if (raw2 != null) {
                JSONObject o = new JSONObject(raw2);
                JSONArray pa = o.optJSONArray("pages");
                if (pa != null) {
                    for (int i = 0; i < pa.length(); i++) {
                        int id = pa.getInt(i);
                        if (!pages.contains(id)) pages.add(id);
                    }
                }
                mainId = o.optInt("main", 1);
                nextId = o.optInt("next", 2);
                readItems(o.optJSONArray("items"));
            } else {
                String legacy = sp.getString("layout", null);
                if (legacy != null) {
                    pages.add(1);
                    readItems(new JSONArray(legacy));
                }
            }
        } catch (JSONException e) {
            map.clear();
            pages.clear();
        }
        if (pages.isEmpty()) {
            pages.add(1);
            mainId = 1;
        }
        if (!pages.contains(mainId)) mainId = pages.get(0);
        int max = 0;
        for (int id : pages) max = Math.max(max, id);
        nextId = Math.max(nextId, max + 1);
    }

    private void readItems(JSONArray a) throws JSONException {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String k = o.getString("k");
            boolean d = o.optBoolean("d", false);
            int p = d ? 0 : o.optInt("p", 1);
            int r = o.optInt("r", 0);
            int c = o.optInt("c", 0);
            if (valid(d, p, r, c) && keyAt(d, p, r, c) == null) map.put(k, new Placement(d, p, r, c));
        }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            JSONArray pa = new JSONArray();
            for (int id : pages) pa.put(id);
            o.put("pages", pa);
            o.put("main", mainId);
            o.put("next", nextId);
            JSONArray items = new JSONArray();
            for (Map.Entry<String, Placement> e : map.entrySet()) {
                Placement p = e.getValue();
                JSONObject it = new JSONObject();
                it.put("k", e.getKey());
                if (p.dock) it.put("d", true); else it.put("p", p.page);
                it.put("r", p.row);
                it.put("c", p.col);
                items.put(it);
            }
            o.put("items", items);
            sp.edit().putString("layout2", o.toString()).remove("layout").apply();
        } catch (JSONException ignored) {
        }
    }

    // ---- Páginas ------------------------------------------------------------

    synchronized int pageCount() { return pages.size(); }

    synchronized int pageId(int index) { return pages.get(index); }

    synchronized int indexOf(int id) { return pages.indexOf(id); }

    synchronized int mainId() { return mainId; }

    synchronized int mainIndex() { return pages.indexOf(mainId); }

    /** Inserta una página vacía en esa posición. Devuelve su id, o -1 si ya se llegó al máximo. */
    synchronized int insertPage(int index) {
        if (pages.size() >= MAX_PAGES) return -1;
        int id = nextId++;
        int at = Math.max(0, Math.min(pages.size(), index));
        pages.add(at, id);
        save();
        return id;
    }

    /** No elimina la principal ni la última. Las apps de esa página dejan de estar en la Home. */
    synchronized boolean removePage(int id) {
        if (pages.size() <= 1 || id == mainId || !pages.contains(id)) return false;
        pages.remove(Integer.valueOf(id));
        Iterator<Map.Entry<String, Placement>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Placement p = it.next().getValue();
            if (!p.dock && p.page == id) it.remove();
        }
        save();
        return true;
    }

    synchronized void movePage(int from, int to) {
        if (from < 0 || to < 0 || from >= pages.size() || to >= pages.size() || from == to) return;
        Integer id = pages.remove(from);
        pages.add(to, id);
        save();
    }

    // ---- Apps ------------------------------------------------------------------

    synchronized Map<String, Placement> snapshot() {
        return new LinkedHashMap<>(map);
    }

    synchronized Placement get(String key) {
        return map.get(key);
    }

    synchronized String keyAt(boolean dock, int page, int r, int c) {
        for (Map.Entry<String, Placement> e : map.entrySet()) {
            Placement p = e.getValue();
            if (p.dock != dock) continue;
            if (!dock && p.page != page) continue;
            if (p.row == r && p.col == c) return e.getKey();
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
     * Coloca (o mueve) una app.
     *  - libre: se coloca.
     *  - ocupado y la app ya estaba colocada: intercambian lugares (también entre páginas).
     *  - ocupado y la app viene del cajón: va al lugar libre más cercano de esa página/dock (o FULL).
     */
    synchronized int drop(String key, boolean dock, int page, int r, int c) {
        int pg = dock ? 0 : page;
        if (!valid(dock, pg, r, c)) return FULL;
        Placement src = map.get(key);
        String occupant = keyAt(dock, pg, r, c);

        if (key.equals(occupant)) return OK;

        if (occupant == null) {
            map.put(key, new Placement(dock, pg, r, c));
            save();
            return OK;
        }
        if (src != null) {
            map.put(occupant, new Placement(src.dock, src.page, src.row, src.col));
            map.put(key, new Placement(dock, pg, r, c));
            save();
            return OK;
        }
        Placement free = nearestFree(dock, pg, r, c);
        if (free == null) return FULL;
        map.put(key, free);
        save();
        return OK;
    }

    private Placement nearestFree(boolean dock, int page, int r0, int c0) {
        int rows = dock ? 1 : HOME_ROWS;
        int cols = dock ? DOCK_SLOTS : HOME_COLS;
        Placement best = null;
        int bestD = Integer.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (keyAt(dock, page, r, c) != null) continue;
                int d = (r - r0) * (r - r0) + (c - c0) * (c - c0);
                if (d < bestD) {
                    bestD = d;
                    best = new Placement(dock, page, r, c);
                }
            }
        }
        return best;
    }
}
