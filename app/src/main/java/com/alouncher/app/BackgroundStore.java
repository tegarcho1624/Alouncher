package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Listas de recursos de "Fondos y temas". Una lista por ámbito y tipo:
 *   home:image · drawer:image · home:video · drawer:video · home:audio
 * Los archivos elegidos se COPIAN a files/backgrounds/<tipo>/ (no se pierden si se borra el original).
 * Las listas y sus ajustes se guardan en SharedPreferences ("backgrounds"), una clave por lista.
 */
final class BackgroundStore {
    private BackgroundStore() {}

    static final String HOME = "home";
    static final String DRAWER = "drawer";
    static final String IMAGE = "image";
    static final String VIDEO = "video";
    static final String AUDIO = "audio";

    static final String MODE_FILL = "fill";       // Rellenar (cubre, recorta)
    static final String MODE_STRETCH = "stretch"; // Estirar
    static final String MODE_CENTER = "center";   // Centrada (se ve completa)

    static final class Item {
        final String id;
        final String file;  // nombre del archivo copiado dentro de files/backgrounds/<tipo>/
        final String name;  // nombre mostrado: el del archivo original SIN extensión

        Item(String id, String file, String name) {
            this.id = id;
            this.file = file;
            this.name = name;
        }

        String ext() {
            int dot = file.lastIndexOf('.');
            return dot >= 0 ? file.substring(dot + 1).toUpperCase(Locale.ROOT) : "";
        }
    }

    static final class Playlist {
        final List<Item> items = new ArrayList<>();
        String cur = "";                 // id del recurso activo ("" = ninguno)
        String mode = MODE_FILL;         // imagen y video
        // Reservados para fondo animado y audio (siguientes sub-fases); se guardan por lista.
        boolean shuffle;
        boolean repeat;                  // repetir la lista
        boolean loop;                    // repetir solo el recurso actual
        boolean mute;
        int volume = 100;

        int indexOf(String id) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(id)) return i;
            }
            return -1;
        }

        Item current() {
            int i = indexOf(cur);
            return i >= 0 ? items.get(i) : null;
        }
    }

    private static int version;

    /** Sube cada vez que se guarda una lista; las pantallas lo comparan para volver a aplicar fondos. */
    static synchronized int version() {
        return version;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("backgrounds", Context.MODE_PRIVATE);
    }

    private static String key(String scope, String type) {
        return scope + ":" + type;
    }

    static Playlist load(Context c, String scope, String type) {
        Playlist p = new Playlist();
        String raw = prefs(c).getString(key(scope, type), null);
        if (raw == null) return p;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray a = o.optJSONArray("items");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.getJSONObject(i);
                    p.items.add(new Item(it.getString("i"), it.getString("f"), it.optString("n", "")));
                }
            }
            p.cur = o.optString("cur", "");
            p.mode = o.optString("mode", MODE_FILL);
            p.shuffle = o.optBoolean("shuf", false);
            p.repeat = o.optBoolean("rep", false);
            p.loop = o.optBoolean("loop", false);
            p.mute = o.optBoolean("mute", false);
            p.volume = o.optInt("vol", 100);
        } catch (JSONException e) {
            return new Playlist();
        }
        return p;
    }

    static void save(Context c, String scope, String type, Playlist p) {
        saveQuiet(c, scope, type, p);
        synchronized (BackgroundStore.class) {
            version++;
        }
    }

    private static void saveQuiet(Context c, String scope, String type, Playlist p) {
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (Item it : p.items) {
                JSONObject j = new JSONObject();
                j.put("i", it.id);
                j.put("f", it.file);
                j.put("n", it.name);
                a.put(j);
            }
            o.put("items", a);
            o.put("cur", p.cur);
            o.put("mode", p.mode);
            o.put("shuf", p.shuffle);
            o.put("rep", p.repeat);
            o.put("loop", p.loop);
            o.put("mute", p.mute);
            o.put("vol", p.volume);
            prefs(c).edit().putString(key(scope, type), o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    // ---- Archivos --------------------------------------------------------------

    static File dir(Context c, String type) {
        return new File(new File(c.getApplicationContext().getFilesDir(), "backgrounds"), type);
    }

    static File fileOf(Context c, String type, Item it) {
        return new File(dir(c, type), it.file);
    }

    static void deleteFile(Context c, String type, Item it) {
        File f = fileOf(c, type, it);
        if (f.exists()) f.delete();
    }

    /**
     * Archivo del recurso activo. Si ya no existe (movido o borrado), salta al siguiente de la lista
     * que sí exista y lo deja como activo; si no queda ninguno, devuelve null (se usa el fondo por defecto).
     */
    static File resolveCurrent(Context c, String scope, String type) {
        Playlist p = load(c, scope, type);
        if (p.cur.isEmpty() || p.items.isEmpty()) return null;
        int start = p.indexOf(p.cur);
        if (start < 0) {
            p.cur = "";
            saveQuiet(c, scope, type, p);
            return null;
        }
        for (int k = 0; k < p.items.size(); k++) {
            Item it = p.items.get((start + k) % p.items.size());
            File f = fileOf(c, type, it);
            if (f.isFile()) {
                if (k > 0) {
                    p.cur = it.id;
                    saveQuiet(c, scope, type, p);
                }
                return f;
            }
        }
        return null;
    }

    /** Copia el archivo elegido a la carpeta interna. Devuelve el recurso, o null si no se pudo (o no es válido). */
    static Item importFile(Context c, String type, Uri uri) {
        String display = queryName(c, uri);
        String ext = extensionOf(display, type);
        File dir = dir(c, type);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;

        String id = UUID.randomUUID().toString();
        String stored = id + "." + ext;
        File dest = new File(dir, stored);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return null;
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException | SecurityException e) {
            dest.delete();
            return null;
        }

        if (IMAGE.equals(type)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(dest.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                dest.delete();
                return null;
            }
        }
        return new Item(id, stored, baseName(display));
    }

    private static String queryName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && cur.getString(i) != null) return cur.getString(i);
            }
        } catch (Exception ignored) {
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) return "recurso";
        int cut = Math.max(seg.lastIndexOf('/'), seg.lastIndexOf(':'));
        return cut >= 0 ? seg.substring(cut + 1) : seg;
    }

    private static String extensionOf(String name, String type) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String e = name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (!e.isEmpty() && e.length() <= 5) return e;
        }
        return IMAGE.equals(type) ? "img" : VIDEO.equals(type) ? "vid" : "aud";
    }

    static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
