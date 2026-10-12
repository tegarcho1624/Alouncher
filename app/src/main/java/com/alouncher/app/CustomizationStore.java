package com.alouncher.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * Guarda las personalizaciones en SharedPreferences.
 * Clave = packageName; valor = JSON pequeño {"n":nombre,"i":uri,"h":true}.
 * Son pocos datos de texto, así que una base de datos sería más pesada sin aportar nada.
 */
final class CustomizationStore {
    private final SharedPreferences sp;

    CustomizationStore(Context ctx) {
        sp = ctx.getSharedPreferences("customizations", Context.MODE_PRIVATE);
    }

    synchronized Customization get(String pkg) {
        Customization c = new Customization();
        String raw = sp.getString(pkg, null);
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

    private void put(String pkg, Customization c) {
        if (c.isEmpty()) {
            sp.edit().remove(pkg).apply();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            if (c.name != null) o.put("n", c.name);
            if (c.icon != null) o.put("i", c.icon);
            if (c.hidden) o.put("h", true);
            sp.edit().putString(pkg, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    synchronized void setName(String pkg, String name) {
        Customization c = get(pkg);
        c.name = name;
        put(pkg, c);
    }

    synchronized void setIcon(String pkg, String uri) {
        Customization c = get(pkg);
        c.icon = uri;
        put(pkg, c);
    }

    synchronized void setHidden(String pkg, boolean hidden) {
        Customization c = get(pkg);
        c.hidden = hidden;
        put(pkg, c);
    }

    synchronized void clear(String pkg) {
        sp.edit().remove(pkg).apply();
    }

    /** ¿Alguna app usa todavía esta imagen? (para soltar el permiso solo cuando nadie la usa) */
    synchronized boolean isIconInUse(String uri) {
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getValue() instanceof String && uri.equals(get(e.getKey()).icon)) return true;
        }
        return false;
    }
}
