# Bloqueador

Bloqueador de publicidad y rastreadores para Android mediante filtrado DNS local (`VpnService`), sin root.

## Estado

**Fase 1 (núcleo):** túnel DNS-only, parser DNS IPv4/IPv6, motor por sufijos con caché LRU,
descarga de Hagezi Multi (formato adblock) con ETag y validación, pantalla de activación con contadores
y aviso de DNS privado.

**Fase 2 (onboarding y modos):** bienvenida, estrategia de apps (todas / todas excepto / solo las
elegidas) con detección de apps de banca y pagos, recarga del túnel sin corte, modos Ligero /
Equilibrado / Estricto, capas extra (anti-evasión DNS, GoodbyeAds, 1Hosts, AdAway, StevenBlack),
exclusiones y bloqueos propios.

**Fase 3 (actualización automática):** WorkManager cada 12 h / 24 h / solo manual, con red y batería
no baja; ETag para no descargar lo que no cambia; una lista que falla no frena las demás; reintentos y
aviso discreto tras 3 periodos fallidos; recarga en caliente si el filtro está activo.

**Fase 4 (historial):** consultas bloqueadas y permitidas con la app que las hizo (Android 10+), agrupadas
y guardadas por lotes en Room, conservación de 1 a 24 h, filtros, búsqueda, detalle con la regla y la lista
de origen, y acciones permitir / bloquear / quitar.

**Fase 5 (bloqueo visual, opcional):** servicio de accesibilidad para marcar elementos de una app y
ocultarlos o pulsarlos, miniaturas en el historial (Android 11+), aviso destacado previo y consumo
mínimo (sin reglas no recibe eventos).

**Fase 6 (eficiencia):** motor compacto (613.531 reglas en ~5 MB), sin trabajo en segundo plano
innecesario, guía de ahorro de energía por fabricante y mediciones en dispositivos reales.

**Distribución:** GitHub Releases con actualización automática dentro de la app y tres canales
(Estable, Beta, Nightly).

**Compatibilidad:** `minSdk 26`, `targetSdk 36`, un único AAB (`./gradlew bundleRelease`).

En compilaciones de depuración se puede forzar la actualización:
`adb shell am broadcast -a app.bloqueador.debug.RUN_UPDATE -p app.bloqueador`

## Compilar

Requiere JDK 17 y el Android SDK (API 36).

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK de depuración: `app/build/outputs/apk/debug/app-debug.apk`.

## Estructura

| Paquete | Contenido |
|---|---|
| `dns` | Parser/constructor de mensajes DNS y paquetes IP/UDP |
| `filter` | Lector de listas, motor de coincidencia, caché LRU |
| `lists` | Descarga y carga de listas |
| `apps` | Estrategia de apps, apps instaladas y lista de banca/pagos |
| `settings` | Configuración persistente |
| `visual` | Servicio de accesibilidad, reglas visuales y miniaturas |
| `appupdate` | Actualizaciones de la app desde GitHub Releases (canales) |
| `history` | Base de datos del historial, registro por lotes y app de origen |
| `update` | Tarea periódica de actualización y aviso de fallos |
| `vpn` | `DnsVpnService` y seguimiento del DNS real de la red |
| `ui` | Pantalla principal (Compose) |

Licencia: GPLv3.
