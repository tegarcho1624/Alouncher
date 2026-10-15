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
 * {"pages":[...], "main":id, "next":n,
 *  "items":[{"i":idInstancia,"k":"paquete/actividad","p":página,"r":fila,"c":columna} | {... "d":true ...}],
 *  "folders":[{"i":id,"n":nombre,"p":página,"r":fila,"c":columna,"x":ampliada,"ic":archivoÍcono,
 *              "bi":archivoFondo,"bc":colorFondo,"m":[{"i":idMiembro,"k":"paquete/actividad"}]}]}
 *
 * Una celda de la Home contiene UN elemento: una instancia de app o una carpeta. El dock solo lleva apps.
 * Las personalizaciones de las apps (ícono, nombre, oculta) NO viven aquí.
 */
final class HomeStore {
    static final int HOME_COLS = 6;
    static final int HOME_ROWS = 4;
    static final int DOCK_SLOTS = 7;
    static final int MAX_PAGES = 7;

    static final int OK = 0;
    static final int FULL = 1;

    /** Un ícono de app colocado. Inmutable: mover = crear otra con la nueva posición y el mismo id. */
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

    /** Una app dentro de una carpeta (con id propio, así puede repetirse la misma app). */
    static final class Member {
        final String id;
        final String key;

        Member(String id, String key) {
            this.id = id;
            this.key = key;
        }
    }

    /** Una carpeta de la Home. Lo que devuelve el almacén son COPIAS: para cambiar algo se usan sus métodos. */
    static final class Folder {
        final String id;
        String name = "";          // "" = nombre por defecto ("Carpeta")
        int page;
        int row;
        int col;
        boolean expanded;          // vista ampliada (sub-fase siguiente)
        String icon = "";          // archivo del ícono personalizado ("" = miniaturas)
        String bgImage = "";       // archivo de la imagen de fondo ("" = ninguna)
        boolean hasBgColor;        // color sólido de fondo
        int bgColor;
        final List<Member> members = new ArrayList<>();

        Folder(String id, int page, int row, int col) {
            this.id = id;
            this.page = page;
            this.row = row;
            this.col = col;
        }

        Folder copy() {
            Folder f = new Folder(id, page, row, col);
            f.name = name;
            f.expanded = expanded;
            f.icon = icon;
            f.bgImage = bgImage;
            f.hasBgColor = hasBgColor;
            f.bgColor = bgColor;
            f.members.addAll(members);
            return f;
        }
    }

    private final SharedPreferences sp;
    private final LinkedHashMap<String, Instance> map = new LinkedHashMap<>();
    private final LinkedHashMap<String, Folder> folders = new LinkedHashMap<>();
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
                readFolders(o.optJSONArray("folders"));
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
            folders.clear();
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
            if (valid(d, p, r, c) && !occupied(d, p, r, c) && !map.containsKey(id)) {
                map.put(id, new Instance(id, k, d, p, r, c));
            }
        }
    }

    private void readFolders(JSONArray a) throws JSONException {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String id = o.getString("i");
            int p = o.optInt("p", 1);
            int r = o.optInt("r", 0);
            int c = o.optInt("c", 0);
            if (!valid(false, p, r, c) || occupied(false, p, r, c) || folders.containsKey(id)) continue;
            Folder f = new Folder(id, p, r, c);
            f.name = o.optString("n", "");
            f.expanded = o.optBoolean("x", false);
            f.icon = o.optString("ic", "");
            f.bgImage = o.optString("bi", "");
            if (o.has("bc")) {
                f.hasBgColor = true;
                f.bgColor = o.getInt("bc");
            }
            JSONArray ma = o.optJSONArray("m");
            if (ma != null) {
                for (int k = 0; k < ma.length(); k++) {
                    JSONObject m = ma.getJSONObject(k);
                    f.members.add(new Member(m.getString("i"), m.getString("k")));
                }
            }
            if (!f.members.isEmpty()) folders.put(id, f); // una carpeta vacía no se conserva
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
            JSONArray fa = new JSONArray();
            for (Folder f : folders.values()) {
                JSONObject fo = new JSONObject();
                fo.put("i", f.id);
                fo.put("n", f.name);
                fo.put("p", f.page);
                fo.put("r", f.row);
                fo.put("c", f.col);
                if (f.expanded) fo.put("x", true);
                if (!f.icon.isEmpty()) fo.put("ic", f.icon);
                if (!f.bgImage.isEmpty()) fo.put("bi", f.bgImage);
                if (f.hasBgColor) fo.put("bc", f.bgColor);
                JSONArray ma = new JSONArray();
                for (Member m : f.members) {
                    JSONObject mo = new JSONObject();
                    mo.put("i", m.id);
                    mo.put("k", m.key);
                    ma.put(mo);
                }
                fo.put("m", ma);
                fa.put(fo);
            }
            o.put("folders", fa);
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

    /** No elimina la principal ni la última. Lo que hubiera en esa página (apps y carpetas) deja de estar en la Home. */
    synchronized boolean removePage(int id) {
        if (pages.size() <= 1 || id == mainId || !pages.contains(id)) return false;
        pages.remove(Integer.valueOf(id));
        Iterator<Instance> it = map.values().iterator();
        while (it.hasNext()) {
            Instance in = it.next();
            if (!in.dock && in.page == id) it.remove();
        }
        Iterator<Folder> fi = folders.values().iterator();
        while (fi.hasNext()) {
            if (fi.next().page == id) fi.remove();
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

    private Folder folderAtRaw(int page, int r, int c) {
        for (Folder f : folders.values()) {
            if (f.page == page && f.row == r && f.col == c) return f;
        }
        return null;
    }

    /** ¿Hay algo (app o carpeta) en esa celda? El dock solo lleva apps. */
    private boolean occupied(boolean dock, int page, int r, int c) {
        return instanceAt(dock, page, r, c) != null || (!dock && folderAtRaw(page, r, c) != null);
    }

    /** Quita SOLO esa instancia. */
    synchronized void remove(String id) {
        if (map.remove(id) != null) save();
    }

    /** Desinstalación: quita todas las instancias de ese paquete y lo saca de las carpetas (las que queden vacías se eliminan). */
    synchronized void removePackage(String pkg) {
        boolean changed = false;
        Iterator<Instance> it = map.values().iterator();
        while (it.hasNext()) {
            if (it.next().key.startsWith(pkg + "/")) {
                it.remove();
                changed = true;
            }
        }
        for (Folder f : folders.values()) {
            if (f.members.removeIf(m -> m.key.startsWith(pkg + "/"))) changed = true;
        }
        if (dropEmptyFolders()) changed = true;
        if (changed) save();
    }

    /** Al abrir el launcher: elimina lo que ya no está instalado (apps y miembros de carpetas) para no dejar celdas "ocupadas" invisibles. */
    synchronized void pruneMissing(Set<String> installedKeys) {
        boolean changed = false;
        Iterator<Instance> it = map.values().iterator();
        while (it.hasNext()) {
            if (!installedKeys.contains(it.next().key)) {
                it.remove();
                changed = true;
            }
        }
        for (Folder f : folders.values()) {
            if (f.members.removeIf(m -> !installedKeys.contains(m.key))) changed = true;
        }
        if (dropEmptyFolders()) changed = true;
        if (changed) save();
    }

    private boolean dropEmptyFolders() {
        boolean changed = false;
        Iterator<Folder> fi = folders.values().iterator();
        while (fi.hasNext()) {
            if (fi.next().members.isEmpty()) {
                fi.remove();
                changed = true;
            }
        }
        return changed;
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
        if (occupied(dock, pg, r, c)) {
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
     * instancia, intercambian lugares (se usa con el dock; en la Home soltar sobre una app crea una carpeta).
     */
    synchronized int move(String id, boolean dock, int page, int r, int c) {
        Instance src = map.get(id);
        if (src == null) return FULL;
        int pg = dock ? 0 : page;
        if (!valid(dock, pg, r, c)) return FULL;
        if (!dock && folderAtRaw(pg, r, c) != null) return FULL; // una carpeta no se pisa
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
                if (occupied(dock, page, r, c)) continue;
                int d = (r - r0) * (r - r0) + (c - c0) * (c - c0);
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{r, c};
                }
            }
        }
        return best;
    }

    // ---- Carpetas ------------------------------------------------------------------

    synchronized List<Folder> folders() {
        List<Folder> out = new ArrayList<>();
        for (Folder f : folders.values()) out.add(f.copy());
        return out;
    }

    synchronized Folder folder(String id) {
        Folder f = folders.get(id);
        return f == null ? null : f.copy();
    }

    synchronized Folder folderAt(int page, int r, int c) {
        Folder f = folderAtRaw(page, r, c);
        return f == null ? null : f.copy();
    }

    /**
     * Se suelta una app SOBRE otra app de la Home: se crea una carpeta nueva con ambas, en el lugar de la receptora.
     * draggedInstanceId == null: la app viene del cajón (no hay instancia que quitar).
     */
    synchronized int dropOnInstance(String key, String draggedInstanceId, String receptorId) {
        Instance rec = map.get(receptorId);
        if (rec == null || rec.dock) return FULL;
        if (receptorId.equals(draggedInstanceId)) return OK;
        Folder f = new Folder(UUID.randomUUID().toString(), rec.page, rec.row, rec.col);
        f.members.add(new Member(rec.id, rec.key));
        String mid = draggedInstanceId != null ? draggedInstanceId : UUID.randomUUID().toString();
        f.members.add(new Member(mid, key));
        map.remove(receptorId);
        if (draggedInstanceId != null) map.remove(draggedInstanceId);
        folders.put(f.id, f);
        save();
        return OK;
    }

    /** Se suelta una app SOBRE una carpeta: se añade a ella (no se crea otra). */
    synchronized int dropOnFolder(String key, String draggedInstanceId, String folderId) {
        Folder f = folders.get(folderId);
        if (f == null) return FULL;
        String mid = draggedInstanceId != null ? draggedInstanceId : UUID.randomUUID().toString();
        if (draggedInstanceId != null) map.remove(draggedInstanceId);
        f.members.add(new Member(mid, key));
        save();
        return OK;
    }

    /** Añade una app al final de la carpeta (desde el botón "+"). Se permite repetir una app, como con las instancias. */
    synchronized int addMember(String folderId, String key) {
        Folder f = folders.get(folderId);
        if (f == null) return FULL;
        f.members.add(new Member(UUID.randomUUID().toString(), key));
        save();
        return OK;
    }

    /** Se suelta una carpeta sobre otra: las apps de la primera pasan a la segunda y la primera desaparece. */
    synchronized int mergeFolders(String srcId, String dstId) {
        if (srcId.equals(dstId)) return OK;
        Folder src = folders.get(srcId);
        Folder dst = folders.get(dstId);
        if (src == null || dst == null) return FULL;
        dst.members.addAll(src.members);
        folders.remove(srcId);
        save();
        return OK;
    }

    /**
     * Mueve una carpeta en la Home. Destino libre: se mueve. Sobre una app: intercambian lugares.
     * Sobre otra carpeta: se fusionan.
     */
    synchronized int moveFolder(String id, int page, int r, int c) {
        Folder f = folders.get(id);
        if (f == null || !valid(false, page, r, c)) return FULL;
        if (f.page == page && f.row == r && f.col == c) return OK;
        Folder dst = folderAtRaw(page, r, c);
        if (dst != null) return mergeFolders(id, dst.id);
        Instance occ = instanceAt(false, page, r, c);
        if (occ != null) map.put(occ.id, occ.at(false, f.page, f.row, f.col)); // la app ocupa el lugar de la carpeta
        f.page = page;
        f.row = r;
        f.col = c;
        save();
        return OK;
    }

    synchronized void renameFolder(String id, String name) {
        Folder f = folders.get(id);
        if (f == null) return;
        f.name = name == null ? "" : name;
        save();
    }

    /** Saca una app de la carpeta y la deja en la Home, en el lugar libre más cercano. FULL si la página no tiene sitio. */
    synchronized int removeMember(String folderId, String memberId) {
        Folder f = folders.get(folderId);
        if (f == null) return OK;
        Member found = null;
        for (Member m : f.members) {
            if (m.id.equals(memberId)) found = m;
        }
        if (found == null) return OK;
        int[] free = nearestFree(false, f.page, f.row, f.col);
        if (free == null) return FULL;
        f.members.remove(found);
        map.put(found.id, new Instance(found.id, found.key, false, f.page, free[0], free[1]));
        if (f.members.isEmpty()) folders.remove(folderId);
        save();
        return OK;
    }

    /**
     * Elimina la carpeta y devuelve sus apps a la Home (la primera ocupa el lugar de la carpeta).
     * @return cuántas apps no cupieron en la página (siguen instaladas y en el cajón, solo dejan de estar en la Home).
     */
    synchronized int deleteFolder(String id) {
        Folder f = folders.remove(id);
        if (f == null) return 0;
        int lost = 0;
        for (Member m : f.members) {
            int[] free = nearestFree(false, f.page, f.row, f.col);
            if (free == null) {
                lost++;
                continue;
            }
            map.put(m.id, new Instance(m.id, m.key, false, f.page, free[0], free[1]));
        }
        save();
        return lost;
    }
}
