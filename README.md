# Alouncher (Fase 2)

Fase 1: grid de 4 columnas, toque = abrir, pulsación larga = menú.
Fase 2: personalización por app (ícono, nombre, ocultar, restaurar).

## Permisos
Ninguno. El selector de imágenes es ACTION_OPEN_DOCUMENT (no pide permisos);
solo se conserva el acceso de lectura a la imagen que tú eliges.

## Datos
SharedPreferences "customizations": clave = packageName, valor = JSON
{"n": nombre, "i": uri de la imagen, "h": true si está oculta}.

## Compilar
GitHub › Actions › Build APK. No cambies `applicationId` ni el keystore.
