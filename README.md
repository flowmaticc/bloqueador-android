# Garbillo

Bloqueador de publicidad, rastreadores y **sitios peligrosos** para **Android**, en todo el móvil
(navegador, apps y juegos), **sin root**, sin cuentas y sin enviar tu navegación a ningún servidor.

*Garbillo: la criba de esparto con la que se limpiaba el grano. Deja pasar lo bueno y retiene lo demás.*

[![Descargar APK](https://img.shields.io/github/v/release/flowmaticc/bloqueador-android?label=Descargar%20APK&logo=github&style=for-the-badge&color=caff33&labelColor=24292f)](https://github.com/flowmaticc/bloqueador-android/releases/latest)
[![Añadir a Obtainium](https://img.shields.io/badge/Obtainium-A%C3%B1adir-6438B5?style=for-the-badge)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.bloqueador%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fflowmaticc%2Fbloqueador-android%22%2C%22author%22%3A%22flowmaticc%22%2C%22name%22%3A%22Garbillo%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22fdroid%5C%22%2C%5C%22invertAPKFilter%5C%22%3Atrue%7D%22%7D)

🌐 **Web:** [flowmaticc.github.io/utilities/bloqueador-android](https://flowmaticc.github.io/utilities/bloqueador-android/)
· 🔒 [Política de privacidad](https://flowmaticc.github.io/utilities/bloqueador-android/privacidad/)

## Cómo funciona

Crea una **VPN local** (no hay ningún servidor al otro lado) por la que solo pasan las **consultas
DNS**. Si un dominio está en una lista de publicidad o rastreo, responde con la dirección vacía
`0.0.0.0` y el anuncio no llega a cargarse; el resto se reenvía a tu DNS habitual o, si lo activas, a
un **DNS cifrado** (Quad9, Cloudflare o Mullvad, por TLS). El tráfico normal no pasa por la app: solo
ve nombres de dominio, nunca el contenido.

- Tres niveles: **Ligero** (Hagezi Light), **Equilibrado** (Hagezi Pro, por defecto) y **Estricto**
  (Hagezi Pro++), más capas opcionales: anti-evasión DNS, lista del fabricante del móvil, juegos y
  apps gratis (GoodbyeAds), 1Hosts, AdAway y StevenBlack.
- **Protección contra amenazas** (activada de serie): phishing, malware, estafas y webs falsas con
  las listas *Threat Intelligence Feeds* de Hagezi, con aviso cuando se bloquea un sitio peligroso.
- Elige qué apps se filtran; las de banca y pagos se excluyen por defecto.
- **Historial local** (1 a 24 h) con la app que hizo cada consulta, y **"Ver en la web"**: carga la web
  donde apareció un dominio y rodea en rojo lo que pone en ella.
- **Reportar una web** para que se analice, con un toque desde el historial o desde el formulario de la
  [web](https://flowmaticc.github.io/utilities/bloqueador-android/#reportar). Se envía solo el dominio, el
  motivo y una nota opcional (servicio en [`reportes/`](reportes/), sobre Cloudflare Workers).
- Listas actualizadas automáticamente (solo si cambian) y consumo mínimo de batería.
- **DNS cifrado opcional** (DNS sobre TLS): nadie en tu red ve ni cambia qué webs visitas. En una
  wifi con página de acceso se usa el DNS de la red hasta que inicias sesión.
- Se actualiza sola desde GitHub Releases, con canal **Estable** (por defecto) o **Beta**.

Si la red devuelve respuestas DNS demasiado grandes para UDP, Garbillo las pide completas por TCP.

Lo que **no** puede quitar ningún bloqueador de este tipo: los anuncios de YouTube y Twitch y las
publicaciones promocionadas de las redes sociales (llegan por los mismos servidores que el contenido).
Para los huecos de anuncios dentro de las webs, combínalo con Brave o Firefox + uBlock Origin.

**Apps que se saltan el DNS del móvil.** Algunas apps preguntan directamente a un DNS público en lugar
de usar el del sistema. Garbillo también filtra las consultas a los más conocidos (Google, Cloudflare,
Quad9, OpenDNS, AdGuard, CleanBrowsing, Control D y Mullvad) y corta su DNS cifrado, así que Chrome,
Firefox y la mayoría de apps vuelven al DNS del sistema, que sí se filtra. Una app que use un servidor
DNS propio poco conocido, o DNS cifrado hacia otras direcciones, sí puede saltarse el filtro: evitarlo
obligaría a pasar todo el tráfico por la VPN, y Garbillo no lo hace a propósito (gastaría más batería
y vería todo lo que haces, no solo los nombres de dominio).

## Instalar

1. Descarga `bloqueador-X.Y.Z.apk` de la [última versión estable](https://github.com/flowmaticc/bloqueador-android/releases/latest).
   El que termina en `-fdroid.apk` es para tiendas como F-Droid: no se actualiza solo.
2. Permite la instalación desde tu navegador o gestor de archivos.
3. Si Play Protect avisa de "app desconocida", pulsa **Más detalles → Instalar de todas formas**
   (la app no está en Google Play; el código es este).
4. Abre la app, elige qué apps proteger y activa el filtro aceptando la solicitud de VPN de Android.

Requiere Android 8.0 o superior.

También puedes instalarla y recibir las actualizaciones con
[Obtainium](https://github.com/ImranR98/Obtainium): [añádela ya configurada](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.bloqueador%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fflowmaticc%2Fbloqueador-android%22%2C%22author%22%3A%22flowmaticc%22%2C%22name%22%3A%22Garbillo%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22fdroid%5C%22%2C%5C%22invertAPKFilter%5C%22%3Atrue%7D%22%7D)
(solo versiones estables y sin el APK `-fdroid`), o añade a mano
`https://github.com/flowmaticc/bloqueador-android` y pon `fdroid` en el filtro de APK invertido.

**Comprueba que el APK es auténtico.** Todas las versiones van firmadas con el mismo certificado. Su
huella SHA-256 es:

```
ee:5b:77:ee:24:58:c5:a6:a4:5e:3b:c6:a3:da:ac:ee:fb:31:79:19:49:3d:4d:85:8e:4f:46:a9:72:56:83:ce
```

Con el SDK de Android: `apksigner verify --print-certs garbillo.apk`. En el móvil la muestran apps
como AppVerifier u Obtainium. Si no coincide, no la instales.

## Permisos

VPN (el filtro local) · Internet y estado de la red · Servicio en primer plano · Notificaciones ·
Ver apps instaladas (para elegir cuáles se filtran) · Instalar actualizaciones · Arranque (para
volver a activar el filtro al encender el móvil y reprogramar las actualizaciones de listas) y
mantener activo (lo añade WorkManager). No pide accesibilidad, captura de pantalla, ubicación,
contactos ni almacenamiento. Explicación detallada en la [web](https://flowmaticc.github.io/utilities/bloqueador-android/#permisos).

## Compilar

Requiere JDK 17 y el Android SDK (API 36).

```bash
./gradlew testGithubDebugUnitTest assembleGithubDebug assembleFdroidDebug
```

APK de depuración: `app/build/outputs/apk/github/debug/app-github-debug.apk` (variante `fdroid`, sin actualizador propio: `apk/fdroid/debug/`). Pruebas automáticas y herramientas
de medición: [docs/PRUEBAS.md](docs/PRUEBAS.md).

### Versiones

- **Beta:** cada cambio en `main`, solo si la variable del repositorio `PUBLICAR_BETA` vale `true`
  (una única pre-release `beta` que se sustituye).
- **Estable:** a mano, desde Actions → Publicar → canal "estable", con aprobación. Antes, actualizar
  `appVersionName` en `gradle.properties` y añadir su sección en `NOVEDADES.md`.

GitHub Actions ([release.yml](.github/workflows/release.yml)) compila, pasa los tests, firma el APK
(v2 + v3), comprueba que la huella del certificado es la de arriba y publica cada versión con un
`update.json` (versión, huella SHA-256 del APK, notas y avisos) que usa la app para actualizarse. El
job que firma no puede escribir en el repositorio y el que publica no ve la clave.

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
| `vpn` | `DnsVpnService`, reenvío de consultas (UDP o DNS cifrado por TLS) y seguimiento de la red |
| `ui` | Interfaz (Jetpack Compose) |

## Licencia

[GNU GPLv3](LICENSE). Las listas de bloqueo pertenecen a sus autores (Hagezi, GoodbyeAds, 1Hosts,
AdAway, StevenBlack), cada una con su licencia; la app las descarga directamente de sus fuentes.
