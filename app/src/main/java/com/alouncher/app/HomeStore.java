package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Estado de la Home en SharedPreferences ("home_layout", clave "layout3"):
 * {"pages":[ids en orden], "main":idPrincipal, "next":siguienteIdDePágina,
 *  "items":[{"i":idInstancia,"k":"paquete/actividad","p":idPágina,"r":fila,"c":columna}
 *         | {"i":...,"k":...,"d":true,"r":0,"c":espacio}]}
 *
 * Cada ícono de la Home o del dock es una INSTANCIA con id propio: puede haber varias de la
 * misma app. Las personalizaciones (ícono, nombre, oculta) NO viven aquí: siguen ligadas a la app.
 * Formatos anteriores ("layout2", "layout") se migran asignando un id a cada ícono.
 */
final class HomeStore {
    static final int HOME_COLS = 6;
    static final int HOME_ROWS = 4;
    static final int DOCK_SLOTS = 7;
    static final int MAX_PAGES = 7;

    static final int OK = 0;
    static final int FULL = 1;

    /** Un ícono colocado. Inmutable: mover = crear otra con la nueva posición y el mismo id. */
    static final class Instance {
        final String id;
        final String key;   // "paquete/actividad" de la app
        final boolean dock;
        final int page;     // 0 en el dock
        final int row;
        final int col;

        Instance(String id, String key, boolean dock, int page, int row, int col) {
            this.id = id;
            this.key = key;
            this.dock = dock;
            this.page = page;
            this.row = row;
            this.col = col;
        }

        Instance at(boolean dock, int page, int row, int col) {
            return new Instance(id, key, dock, page, row, col);
        }

        String packageName() {
            int i = key.indexOf('/');
            return i < 0 ? key : key.substring(0, i);
        }
    }

    private final SharedPreferences sp;
    private final LinkedHashMap<String, Instance> map = new LinkedHashMap<>();
    private final ArrayList<Integer> pages = new ArrayList<>();
    private int mainId = 1;
    private int nextId = 2;

    HomeStore(Context ctx) {
        sp = ctx.getSharedPreferences("home_layout", Context.MODE_PRIVATE);
        boolean migrated = load();
        if (migrated) save(); // deja los ids nuevos guardados
    }

    private boolean valid(boolean dock, int page, int r, int c) {
        if (dock) return r == 0 && c >= 0 && c < DOCK_SLOTS;
        return pages.contains(page) && r >= 0 && r < HOME_ROWS && c >= 0 && c < HOME_COLS;
    }

    /** @return true si se leyó un formato anterior (hay que volver a guardar). */
    private boolean load() {
        boolean migrated = false;
        try {
            String raw3 = sp.getString("layout3", null);
            String raw2 = sp.getString("layout2", null);
            String raw = raw3 != null ? raw3 : raw2;
            if (raw != null) {
                migrated = raw3 == null;
                JSONObject o = new JSONObject(raw);
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
                    migrated = true;
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
        return migrated;
    }

    private void readItems(JSONArray a) throws JSONException {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String k = o.getString("k");
            String id = o.has("i") ? o.getString("i") : UUID.randomUUID().toString();
            boolean d = o.optBoolean("d", false);
            int p = d ? 0 : o.optInt("p", 1);
            int r = o.optInt("r", 0);
            int c = o.optInt("c", 0);
            if (valid(d, p, r, c) && instanceAt(d, p, r, c) == null && !map.containsKey(id)) {
                map.put(id, new Instance(id, k, d, p, r, c));
            }
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
            for (Instance in : map.values()) {
                JSONObject it = new JSONObject();
                it.put("i", in.id);
                it.put("k", in.key);
                if (in.dock) it.put("d", true); else it.put("p", in.page);
                it.put("r", in.row);
                it.put("c", in.col);
                items.put(it);
            }
            o.put("items", items);
            sp.edit().putString("layout3", o.toString()).remove("layout2").remove("layout").apply();
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

    /** No elimina la principal ni la última. Los íconos de esa página dejan de estar en la Home. */
    synchronized boolean removePage(int id) {
        if (pages.size() <= 1 || id == mainId || !pages.contains(id)) return false;
        pages.remove(Integer.valueOf(id));
        Iterator<Instance> it = map.values().iterator();
        while (it.hasNext()) {
            Instance in = it.next();
            if (!in.dock && in.page == id) it.remove();
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

    // ---- Instancias ------------------------------------------------------------

    synchronized List<Instance> snapshot() {
        return new ArrayList<>(map.values());
    }

    synchronized Instance get(String id) {
        return map.get(id);
    }

    synchronized Instance instanceAt(boolean dock, int page, int r, int c) {
        for (Instance in : map.values()) {
            if (in.dock != dock) continue;
            if (!dock && in.page != page) continue;
            if (in.row == r && in.col == c) return in;
        }
        return null;
    }

    /** Quita SOLO esa instancia. */
    synchronized void remove(String id) {
        if (map.remove(id) != null) save();
    }

    /** Desinstalación: quita todas las instancias de ese paquete. */
    synchronized void removePackage(String pkg) {
        boolean changed = false;
        Iterator<Instance> it = map.values().iterator();
        while (it.hasNext()) {
            if (it.next().key.startsWith(pkg + "/")) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Al abrir el launcher: elimina instancias de apps que ya no están instaladas. */
    synchronized void pruneMissing(Set<String> installedKeys) {
        boolean changed = false;
        Iterator<Instance> it = map.values().iterator();
        while (it.hasNext()) {
            if (!installedKeys.contains(it.next().key)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Viene del cajón: crea una instancia NUEVA (con id nuevo) aunque la app ya esté en la Home.
     * Si el lugar está ocupado, va al lugar libre más cercano de esa página/dock (o FULL si no hay).
     */
    synchronized int addNew(String key, boolean dock, int page, int r, int c) {
        int pg = dock ? 0 : page;
        if (!valid(dock, pg, r, c)) return FULL;
        int rr = r;
        int cc = c;
        if (instanceAt(dock, pg, r, c) != null) {
            int[] free = nearestFree(dock, pg, r, c);
            if (free == null) return FULL;
            rr = free[0];
            cc = free[1];
        }
        String id = UUID.randomUUID().toString();
        map.put(id, new Instance(id, key, dock, pg, rr, cc));
        save();
        return OK;
    }

    /**
     * Mueve una instancia existente (no crea otra). Si el destino está ocupado por otra
     * instancia, intercambian lugares (también entre páginas o entre Home y dock).
     */
    synchronized int move(String id, boolean dock, int page, int r, int c) {
        Instance src = map.get(id);
        if (src == null) return FULL;
        int pg = dock ? 0 : page;
        if (!valid(dock, pg, r, c)) return FULL;
        Instance occ = instanceAt(dock, pg, r, c);
        if (occ != null && occ.id.equals(id)) return OK;
        if (occ == null) {
            map.put(id, src.at(dock, pg, r, c));
            save();
            return OK;
        }
        map.put(occ.id, occ.at(src.dock, src.page, src.row, src.col));
        map.put(id, src.at(dock, pg, r, c));
        save();
        return OK;
    }

    private int[] nearestFree(boolean dock, int page, int r0, int c0) {
        int rows = dock ? 1 : HOME_ROWS;
        int cols = dock ? DOCK_SLOTS : HOME_COLS;
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (instanceAt(dock, page, r, c) != null) continue;
                int d = (r - r0) * (r - r0) + (c - c0) * (c - c0);
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{r, c};
                }
            }
        }
        return best;
    }
}
