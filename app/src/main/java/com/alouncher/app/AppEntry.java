package com.alouncher.app;

import android.content.ComponentName;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

/** Una app lanzable con sus datos originales y su personalización aplicada. */
final class AppEntry {
    final String packageName;
    final ComponentName component;
    final String key;               // paquete + actividad: identifica cada entrada del launcher

    final String originalLabel;
    final Drawable originalIcon;

    final String customLabel;       // null si no hay
    final String customIconPath;    // URI guardada; null si no hay
    final Drawable customIcon;      // decodificada; null si no hay o si la imagen ya no existe
    final boolean hidden;

    AppEntry(String packageName, ComponentName component,
             String originalLabel, Drawable originalIcon,
             String customLabel, String customIconPath, Drawable customIcon,
             boolean hidden) {
        this.packageName = packageName;
        this.component = component;
        this.key = packageName + "/" + component.getClassName();
        this.originalLabel = originalLabel;
        this.originalIcon = originalIcon;
        this.customLabel = customLabel;
        this.customIconPath = customIconPath;
        this.customIcon = customIcon;
        this.hidden = hidden;
    }

    String key() {
        return key;
    }

    /** Nombre que se muestra: el personalizado si existe. */
    String label() {
        return customLabel != null ? customLabel : originalLabel;
    }

    /** Ícono que se muestra: si la imagen elegida ya no existe, cae al original. */
    Drawable icon() {
        return customIcon != null ? customIcon : originalIcon;
    }

    /**
     * Copia propia del ícono. Un mismo Drawable no debe mostrarse en dos vistas a la vez
     * (cajón + Home + dock tienen tamaños distintos), así que cada vista pide el suyo.
     */
    Drawable newIcon(Resources res) {
        Drawable d = icon();
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(res).mutate() : d;
    }

    boolean hasCustomization() {
        return customLabel != null || customIconPath != null || hidden;
    }
}
