# Bloqueador

Bloqueador de publicidad y rastreadores para **Android**, en todo el móvil (navegador, apps y juegos),
**sin root**, sin cuentas y sin enviar datos a ningún servidor.

🌐 **Web:** [flowmaticc.github.io/utilities/bloqueador-android](https://flowmaticc.github.io/utilities/bloqueador-android/)
· 📥 **Descarga:** [última versión estable](https://github.com/flowmaticc/bloqueador-android/releases/latest)
· 🔒 [Política de privacidad](https://flowmaticc.github.io/utilities/bloqueador-android/privacidad/)

## Cómo funciona

Crea una **VPN local** (no hay ningún servidor al otro lado) por la que solo pasan las **consultas
DNS**. Si un dominio está en una lista de publicidad o rastreo, responde que no existe y el anuncio no
llega a cargarse; el resto se reenvía a tu DNS habitual. El tráfico normal no pasa por la app: solo ve
nombres de dominio, nunca el contenido.

- Tres niveles: **Ligero** (Hagezi Light), **Equilibrado** (Hagezi Pro, por defecto) y **Estricto**
  (Hagezi Pro++), más capas opcionales: anti-evasión DNS, lista del fabricante del móvil, juegos y
  apps gratis (GoodbyeAds), 1Hosts, AdAway y StevenBlack.
- Elige qué apps se filtran; las de banca y pagos se excluyen por defecto.
- **Historial local** (1 a 24 h) con la app que hizo cada consulta, y **"Ver en la web"**: carga la web
  donde apareció un dominio y rodea en rojo lo que pone en ella.
- Listas actualizadas automáticamente (solo si cambian) y consumo mínimo de batería.
- Se actualiza sola desde GitHub Releases, con canal **Estable** (por defecto), **Beta** o **Nightly**.

Lo que **no** puede quitar ningún bloqueador de este tipo: los anuncios de YouTube y Twitch y las
publicaciones promocionadas de las redes sociales (llegan por los mismos servidores que el contenido).
Para los huecos de anuncios dentro de las webs, combínalo con Brave o Firefox + uBlock Origin.

## Instalar

1. Descarga el APK de la [última versión estable](https://github.com/flowmaticc/bloqueador-android/releases/latest).
2. Permite la instalación desde tu navegador o gestor de archivos.
3. Si Play Protect avisa de "app desconocida", pulsa **Más detalles → Instalar de todas formas**
   (la app no está en Google Play; el código es este).
4. Abre la app, elige qué apps proteger y activa el filtro aceptando la solicitud de VPN de Android.

Requiere Android 8.0 o superior.

## Permisos

VPN (el filtro local) · Internet y estado de la red · Servicio en primer plano · Notificaciones ·
Ver apps instaladas (para elegir cuáles se filtran) · Instalar actualizaciones · Arranque y
mantener activo (los añade WorkManager). No pide accesibilidad, captura de pantalla, ubicación,
contactos ni almacenamiento. Explicación detallada en la [web](https://flowmaticc.github.io/utilities/bloqueador-android/#permisos).

## Compilar

Requiere JDK 17 y el Android SDK (API 36).

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK de depuración: `app/build/outputs/apk/debug/app-debug.apk`. Pruebas automáticas y herramientas
de medición: [docs/PRUEBAS.md](docs/PRUEBAS.md).

### Versiones

- **Nightly:** cada cambio en `main` (pre-release `nightly`, se sustituye).
- **Beta:** etiqueta `vX.Y.Z-beta.N` o `vX.Y.Z-rc.N`.
- **Estable:** etiqueta `vX.Y.Z` (antes, actualizar `appVersionName` en `gradle.properties`).

GitHub Actions ([release.yml](.github/workflows/release.yml)) compila, pasa los tests, firma el APK
y publica cada versión con un `update.json` (versionCode, nombre y huella SHA-256) que usa la app
para actualizarse. La firma usa secretos cifrados del repositorio.

### Estructura

| Paquete | Contenido |
|---|---|
| `dns` | Parser/constructor de mensajes DNS y paquetes IP/UDP |
| `filter` | Lector de listas, motor de coincidencia compacto, caché LRU |
| `lists` | Descarga y carga de listas |
| `apps` | Estrategia de apps, apps instaladas y lista de banca/pagos |
| `settings` | Configuración persistente |
| `appupdate` | Actualizaciones de la app desde GitHub Releases (canales) |
| `history` | Historial, registro por lotes, app de origen y "Ver en la web" |
| `update` | Tarea periódica de actualización de listas |
| `vpn` | `DnsVpnService`, reenvío de consultas y seguimiento del DNS de la red |
| `ui` | Interfaz (Jetpack Compose) |

## Licencia

[GNU GPLv3](LICENSE). Las listas de bloqueo pertenecen a sus autores (Hagezi, GoodbyeAds, 1Hosts,
AdAway, StevenBlack), cada una con su licencia; la app las descarga directamente de sus fuentes.
