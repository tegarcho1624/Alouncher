package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guarda las personalizaciones en SharedPreferences.
 * Clave = "paquete/actividad" (cada entrada del launcher es independiente, así una app
 * con dos íconos de lanzamiento no comparte nombre ni ícono entre ellos).
 * Valor = JSON pequeño {"n":nombre,"i":uri,"h":true}.
 */
final class CustomizationStore {
    private final SharedPreferences sp;

    CustomizationStore(Context ctx) {
        sp = ctx.getSharedPreferences("customizations", Context.MODE_PRIVATE);
    }

    synchronized Customization get(String key) {
        Customization c = new Customization();
        String raw = sp.getString(key, null);
        if (raw == null) return c;
        try {
            JSONObject o = new JSONObject(raw);
            c.name = o.has("n") ? o.getString("n") : null;
            c.icon = o.has("i") ? o.getString("i") : null;
            c.hidden = o.optBoolean("h", false);
        } catch (JSONException e) {
            return new Customization();
        }
        return c;
    }

    private void put(String key, Customization c) {
        if (c.isEmpty()) {
            sp.edit().remove(key).apply();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            if (c.name != null) o.put("n", c.name);
            if (c.icon != null) o.put("i", c.icon);
            if (c.hidden) o.put("h", true);
            sp.edit().putString(key, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    synchronized void setName(String key, String name) {
        Customization c = get(key);
        c.name = name;
        put(key, c);
    }

    synchronized void setIcon(String key, String uri) {
        Customization c = get(key);
        c.icon = uri;
        put(key, c);
    }

    synchronized void setHidden(String key, boolean hidden) {
        Customization c = get(key);
        c.hidden = hidden;
        put(key, c);
    }

    synchronized void clear(String key) {
        sp.edit().remove(key).apply();
    }

    /**
     * Migración: lo guardado antes solo por paquete se copia a cada entrada de ese
     * paquete (queda igual que se veía) y la clave vieja se elimina.
     */
    synchronized void migrate(String pkg, List<String> keys) {
        String raw = sp.getString(pkg, null);
        if (raw == null) return;
        SharedPreferences.Editor ed = sp.edit();
        for (String k : keys) {
            if (!sp.contains(k)) ed.putString(k, raw);
        }
        ed.remove(pkg).apply();
    }

    /** Borra todo lo de un paquete (desinstalación). Devuelve las imágenes que usaba. */
    synchronized List<String> clearPackage(String pkg) {
        List<String> icons = new ArrayList<>();
        SharedPreferences.Editor ed = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.equals(pkg) || k.startsWith(pkg + "/")) {
                String icon = get(k).icon;
                if (icon != null) icons.add(icon);
                ed.remove(k);
            }
        }
        ed.apply();
        return icons;
    }

    /** ¿Alguna entrada usa todavía esta imagen? (para soltar el permiso solo cuando nadie la usa) */
    synchronized boolean isIconInUse(String uri) {
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getValue() instanceof String && uri.equals(get(e.getKey()).icon)) return true;
        }
        return false;
    }
}
