package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
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
        final String artist; // solo audio, si el archivo lo trae en sus metadatos ("" si no)

        Item(String id, String file, String name) {
            this(id, file, name, "");
        }

        Item(String id, String file, String name, String artist) {
            this.id = id;
            this.file = file;
            this.name = name;
            this.artist = artist != null ? artist : "";
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
        boolean off;                     // audio: pausado A MANO (no se reanuda solo hasta pulsar ▶)
        int pos;                         // audio: posición (ms) donde quedó al salir de la Home

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

    private static Playlist fresh(String type) {
        Playlist p = new Playlist();
        boolean video = VIDEO.equals(type);
        p.loop = video;   // un fondo animado se repite solo
        p.mute = video;   // y va silenciado
        return p;
    }

    static Playlist load(Context c, String scope, String type) {
        Playlist p = fresh(type);
        String raw = prefs(c).getString(key(scope, type), null);
        if (raw == null) return p;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray a = o.optJSONArray("items");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.getJSONObject(i);
                    p.items.add(new Item(it.getString("i"), it.getString("f"), it.optString("n", ""),
                            it.optString("a", "")));
                }
            }
            p.cur = o.optString("cur", "");
            p.mode = o.optString("mode", MODE_FILL);
            p.shuffle = o.optBoolean("shuf", false);
            p.repeat = o.optBoolean("rep", false);
            p.loop = o.optBoolean("loop", p.loop);
            p.mute = o.optBoolean("mute", p.mute);
            p.volume = o.optInt("vol", 100);
            p.off = o.optBoolean("off", false);
            p.pos = o.optInt("pos", 0);
        } catch (JSONException e) {
            return fresh(type);
        }
        return p;
    }

    static void save(Context c, String scope, String type, Playlist p) {
        saveQuiet(c, scope, type, p);
        synchronized (BackgroundStore.class) {
            version++;
        }
    }

    /** Guarda sin subir la versión: para cambios que hace el propio reproductor (p. ej. pasar al siguiente video). */
    static void saveNoBump(Context c, String scope, String type, Playlist p) {
        saveQuiet(c, scope, type, p);
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
                if (!it.artist.isEmpty()) j.put("a", it.artist);
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
            o.put("off", p.off);
            o.put("pos", p.pos);
            prefs(c).edit().putString(key(scope, type), o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** ¿El fondo animado de la Home suena? = hay uno activo, no está silenciado y su volumen es mayor que 0. */
    static boolean homeVideoSounds(Context c) {
        Playlist p = load(c, HOME, VIDEO);
        return p.current() != null && !p.mute && p.volume > 0;
    }

    // ---- Archivos --------------------------------------------------------------

    static File dir(Context c, String type) {
        return new File(new File(c.getApplicationContext().getFilesDir(), "backgrounds"), type);
    }

    static File fileOf(Context c, String type, Item it) {
        return new File(dir(c, type), it.file);
    }

    /** Miniatura de un video (un cuadro cerca del inicio). */
    static File thumbFile(Context c, Item it) {
        return thumbFileById(c, VIDEO, it.id);
    }

    /** Miniatura de un recurso: cuadro del video o carátula del audio. */
    static File thumbFile(Context c, String type, Item it) {
        return thumbFileById(c, type, it.id);
    }

    private static File thumbFileById(Context c, String type, String id) {
        return new File(new File(dir(c, type), "thumbs"), id + ".jpg");
    }

    static void deleteFile(Context c, String type, Item it) {
        File f = fileOf(c, type, it);
        if (f.exists()) f.delete();
        if (VIDEO.equals(type) || AUDIO.equals(type)) {
            File t = thumbFile(c, type, it);
            if (t.exists()) t.delete();
        }
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
        String artist = "";
        if (AUDIO.equals(type)) {
            // Comprueba que sea un audio legible; toma artista y carátula si el archivo los trae
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(dest.getAbsolutePath());
                if (!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                    dest.delete();
                    return null;
                }
                String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (a == null || a.trim().isEmpty()) {
                    a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                }
                if (a != null) artist = a.trim();
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) {
                    android.graphics.Bitmap cover = ImageUtil.coverThumb(pic, 192);
                    if (cover != null) ImageUtil.saveJpeg(cover, thumbFileById(c, AUDIO, id));
                }
            } catch (Exception e) {
                dest.delete();
                return null;
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        Item item = new Item(id, stored, baseName(display), artist);
        if (VIDEO.equals(type)) {
            // Comprueba que sea un video legible y deja lista su miniatura (un cuadro cerca del inicio)
            android.graphics.Bitmap frame = ImageUtil.videoFrame(dest, 192);
            if (frame == null) {
                dest.delete();
                return null;
            }
            ImageUtil.saveJpeg(frame, thumbFile(c, item));
        }
        return item;
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
