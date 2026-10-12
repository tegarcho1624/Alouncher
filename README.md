# Alouncher (Fase 1)

Launcher personal: grid de 4 columnas con todas las apps, toque = abrir,
pulsación larga = menú (Abrir / Información de la app).

- **Sin permisos** (ni QUERY_ALL_PACKAGES, ni internet). Usa `<queries>` en el manifest.
- Se registra como HOME, pero NO se fuerza como predeterminado: eso se elige en
  Ajustes › Apps › Apps predeterminadas › App de inicio.
- minSdk 26 (Android 8.0), targetSdk 34.

## Compilar
Sube la carpeta a GitHub (con `.github` en la raíz) → Actions › Build APK → descarga `Alouncher-APK`.
La firma es fija (`alouncher-debug.keystore`): cada versión se instala encima de la anterior.
No cambies `applicationId` ni el keystore.
