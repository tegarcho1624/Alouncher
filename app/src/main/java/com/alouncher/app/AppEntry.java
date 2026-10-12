package com.alouncher.app;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** Una app lanzable: nombre, ícono y componente a abrir. */
final class AppEntry {
    final String label;
    final String packageName;
    final ComponentName component;
    final Drawable icon;

    AppEntry(String label, String packageName, ComponentName component, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.component = component;
        this.icon = icon;
    }
}
