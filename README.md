# Alouncher (Fase 3A)

Fase 1: grid de 4 columnas, toque = abrir, pulsación larga = menú.
Fase 2: personalización por entrada del launcher (ícono, nombre, ocultar, restaurar).

## Permisos
Ninguno. El selector de imágenes es ACTION_OPEN_DOCUMENT (no pide permisos);
solo se conserva el acceso de lectura a la imagen que tú eliges.

## Datos
SharedPreferences "customizations": clave = "paquete/actividad", valor = JSON
{"n": nombre, "i": uri de la imagen, "h": true si está oculta}.

## Compilar
GitHub › Actions › Build APK. No cambies `applicationId` ni el keystore.

## Fase 3A
- Home (fondo del sistema + cuadrícula 6×4 + dock de 7 espacios) y cajón de apps como capa que sube.
- Una sola Activity: el cajón es una capa de la misma ventana para poder arrastrar a la Home.
- Estado de Home/dock en SharedPreferences "home_layout" (JSON con paquete/actividad y posición).
- Constantes ajustables: HomeStore.HOME_COLS / HOME_ROWS / DOCK_SLOTS y MainActivity.DRAWER_COLUMNS.
