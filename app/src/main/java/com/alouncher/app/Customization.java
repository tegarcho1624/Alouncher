package com.alouncher.app;

/** Lo que el usuario personalizó de UNA app (clave: packageName). */
final class Customization {
    String name;        // nombre personalizado; null = el original
    String icon;        // URI (content://...) de la imagen elegida; null = el original
    boolean hidden;     // oculta del grid

    boolean isEmpty() {
        return name == null && icon == null && !hidden;
    }
}
