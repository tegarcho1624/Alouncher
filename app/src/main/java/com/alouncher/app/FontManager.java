package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fuente activa del launcher.
 *  - Fuentes incluidas: "Predeterminada" (el aspecto original) y las familias que trae Android.
 *  - Fuentes importadas: archivos .ttf/.otf copiados a files/fonts/ con su nombre original.
 *  - La elección se guarda en SharedPreferences ("settings", clave "font").
 *  - Un tema no sirve para fuentes cargadas desde archivo, así que apply() recorre las vistas.
 */
final class FontManager {
    private FontManager() {}

    static final class Option {
        final String id;        // "sys:serif" o "file:Nombre.ttf"
        final String name;      // lo que se muestra: nombre del archivo SIN extensión
        final boolean imported;

        Option(String id, String name, boolean imported) {
            this.id = id;
            this.name = name;
            this.imported = imported;
        }
    }

    static final int ERR_NONE = 0;
    static final int ERR_EXT = 1;
    static final int ERR_INVALID = 2;
    static final int ERR_IO = 3;

    static final class ImportResult {
        final int error;
        final String name;
        final boolean replaced;

        ImportResult(int error, String name, boolean replaced) {
            this.error = error;
            this.name = name;
            this.replaced = replaced;
        }
    }

    private static final String PREFS = "settings";
    private static final String KEY = "font";
    private static final String DEFAULT_ID = "sys:default";

    // {id, nombre mostrado, familia de Android ("" = el aspecto original de Alouncher)}
    private static final String[][] BUILT_IN = {
            {"sys:default", "Predeterminada", ""},
            {"sys:sans-serif", "Sans serif", "sans-serif"},
            {"sys:sans-serif-light", "Sans serif ligera", "sans-serif-light"},
            {"sys:sans-serif-condensed", "Sans serif condensada", "sans-serif-condensed"},
            {"sys:serif", "Serif", "serif"},
            {"sys:monospace", "Monoespaciada", "monospace"},
            {"sys:cursive", "Cursiva", "cursive"},
            {"sys:casual", "Casual", "casual"},
    };

    private static boolean inited;
    private static String activeId = DEFAULT_ID;
    private static Typeface active;   // null = aspecto original (no se toca ninguna vista)
    private static int version;
    private static final Map<String, Typeface> cache = new HashMap<>();

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File fontsDir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "fonts");
    }

    /** Carga la fuente guardada. Si el archivo ya no existe o falla, cae a la predeterminada sin errores. */
    static synchronized void init(Context ctx) {
        if (inited) return;
        inited = true;
        String id = prefs(ctx).getString(KEY, DEFAULT_ID);
        Typeface tf = null;
        if (!DEFAULT_ID.equals(id)) {
            tf = load(ctx, id);
            if (tf == null) {
                id = DEFAULT_ID;
                prefs(ctx).edit().putString(KEY, id).apply();
            }
        }
        activeId = id;
        active = tf;
    }

    static synchronized String activeId() { return activeId; }

    /** Sube cada vez que cambia la fuente activa (las pantallas lo comparan para volver a aplicarla). */
    static synchronized int version() { return version; }

    private static Typeface load(Context ctx, String id) {
        Typeface cached = cache.get(id);
        if (cached != null) return cached;
        Typeface tf = null;
        try {
            if (id.startsWith("sys:")) {
                for (String[] b : BUILT_IN) {
                    if (b[0].equals(id)) {
                        tf = b[2].isEmpty() ? Typeface.DEFAULT : Typeface.create(b[2], Typeface.NORMAL);
                    }
                }
            } else if (id.startsWith("file:")) {
                File f = new File(fontsDir(ctx), id.substring(5));
                if (f.isFile()) tf = Typeface.createFromFile(f);
            }
        } catch (Exception | OutOfMemoryError e) {
            tf = null;
        }
        if (tf != null) cache.put(id, tf);
        return tf;
    }

    /** Tipografía para la vista previa de una fuente en la lista. */
    static synchronized Typeface preview(Context ctx, Option o) {
        Typeface t = load(ctx, o.id);
        return t != null ? t : Typeface.DEFAULT;
    }

    static List<Option> options(Context ctx) {
        List<Option> out = new ArrayList<>();
        for (String[] b : BUILT_IN) out.add(new Option(b[0], b[1], false));

        List<Option> imported = new ArrayList<>();
        File[] files = fontsDir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && isFontName(n)) imported.add(new Option("file:" + n, baseName(n), true));
            }
        }
        Collator col = Collator.getInstance();
        imported.sort((a, b) -> col.compare(a.name, b.name));
        out.addAll(imported);
        return out;
    }

    static synchronized boolean select(Context ctx, Option o) {
        Typeface tf = null;
        if (!DEFAULT_ID.equals(o.id)) {
            tf = load(ctx, o.id);
            if (tf == null) return false;
        }
        activeId = o.id;
        active = tf;
        version++;
        prefs(ctx).edit().putString(KEY, o.id).apply();
        return true;
    }

    // ---- Importar --------------------------------------------------------------

    /** Copia la fuente elegida a files/fonts/ (con su nombre original). Si ya había una con ese nombre, la reemplaza. */
    static ImportResult importFont(Context ctx, Uri uri) {
        String display = queryName(ctx, uri);
        if (display == null) return new ImportResult(ERR_EXT, "", false);
        String clean = display.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (!isFontName(clean)) return new ImportResult(ERR_EXT, "", false);
        String base = baseName(clean);

        File dir = fontsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) return new ImportResult(ERR_IO, base, false);
        File tmp = new File(dir, ".import.tmp");

        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return new ImportResult(ERR_IO, base, false);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException | SecurityException e) {
            tmp.delete();
            return new ImportResult(ERR_IO, base, false);
        }

        if (!looksLikeFont(tmp)) {
            tmp.delete();
            return new ImportResult(ERR_INVALID, base, false);
        }

        // Reemplaza cualquier fuente con el mismo nombre (aunque tenga otra extensión)
        boolean replaced = false;
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (!f.equals(tmp) && isFontName(f.getName())
                        && baseName(f.getName()).equalsIgnoreCase(base)) {
                    f.delete();
                    replaced = true;
                }
            }
        }
        File dest = new File(dir, clean);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return new ImportResult(ERR_IO, base, false);
        }
        synchronized (FontManager.class) {
            cache.keySet().removeIf(k -> k.startsWith("file:"));
        }

        try {
            if (Typeface.createFromFile(dest) == null) throw new IllegalStateException("null");
        } catch (Exception | OutOfMemoryError e) {
            dest.delete();
            return new ImportResult(ERR_INVALID, base, false);
        }

        // Si era la fuente activa, se recarga con el archivo nuevo
        synchronized (FontManager.class) {
            if (activeId.startsWith("file:") && baseName(activeId.substring(5)).equalsIgnoreCase(base)) {
                activeId = "file:" + clean;
                active = load(ctx, activeId);
                if (active == null) activeId = DEFAULT_ID;
                prefs(ctx).edit().putString(KEY, activeId).apply();
                version++;
            }
        }
        return new ImportResult(ERR_NONE, base, replaced);
    }

    private static String queryName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) return null;
        int cut = Math.max(seg.lastIndexOf('/'), seg.lastIndexOf(':'));
        return cut >= 0 ? seg.substring(cut + 1) : seg;
    }

    /** TrueType (00 01 00 00 / "true"), OpenType-CFF ("OTTO") o colección ("ttcf"). */
    private static boolean looksLikeFont(File f) {
        byte[] h = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(h) != 4) return false;
        } catch (IOException e) {
            return false;
        }
        String s = new String(h, StandardCharsets.ISO_8859_1);
        return (h[0] == 0 && h[1] == 1 && h[2] == 0 && h[3] == 0)
                || s.equals("OTTO") || s.equals("true") || s.equals("ttcf");
    }

    private static boolean isFontName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".ttf") || l.endsWith(".otf");
    }

    /** Nombre del archivo SIN la extensión. */
    static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ---- Aplicar a las vistas --------------------------------------------------

    /** Aplica la fuente activa a la vista y a todas sus hijas. Con "Predeterminada" restaura el aspecto original. */
    static void apply(View v) {
        if (v == null) return;
        if (v instanceof TextView) applyTo((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    static void applyTo(TextView tv) {
        Typeface orig = (Typeface) tv.getTag(R.id.tag_orig_typeface);
        if (orig == null) {
            orig = tv.getTypeface();
            if (orig == null) orig = Typeface.DEFAULT;
            tv.setTag(R.id.tag_orig_typeface, orig);
        }
        Typeface custom;
        synchronized (FontManager.class) {
            custom = active;
        }
        if (custom == null) {
            tv.setTypeface(orig);                       // aspecto original (negritas/serif de Alouncher)
        } else {
            tv.setTypeface(custom, orig.getStyle());    // conserva negrita/cursiva si el texto las tenía
        }
    }
}
