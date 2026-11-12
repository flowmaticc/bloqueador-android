# Pruebas

| Prueba | Qué comprueba | Cómo se lanza |
|---|---|---|
| Tests unitarios | Parser de listas, motor de coincidencias, DNS, historial, actualizador | `gradlew testDebugUnitTest` (también en cada publicación) |
| Prueba de humo | En emulador o móvil: instalación, bienvenida, VPN, bloqueo, resolución normal, historial, apagado | `tools/smoke-test.sh <serial> <carpeta>` |
| Cobertura turtlecute (listas) | Qué % de los dominios de [adblock.turtlecute.org](https://adblock.turtlecute.org) bloquea cada modo y capa | `gradlew testDebugUnitTest --tests '*TurtlecuteCoverageTest' -Pturtlecute` → `app/build/reports/turtlecute.md` |
| Turtlecute en el dispositivo | Lo mismo, pero a través del filtro real del móvil (VPN activa) | `tools/turtlecute-device.sh <serial> [carpeta]` (la prueba de humo ya lo incluye) |
| Cobertura por sitio | Qué % de los dominios de cualquier web (o test) bloquea cada modo y capa | `gradlew :app:testDebugUnitTest --tests '*SiteCoverageTest' -PsiteHosts=<carpeta>` → `app/build/reports/sites.md`. Cada fichero: dominios separados por espacios, o `categoría<TAB>dominio`. |
| Prueba de carga larga | Que el filtro aguanta horas sin tocar el móvil, también en reposo profundo | App auxiliar `loadtest` (ver abajo) |

## adblock.turtlecute.org

Test público de bloqueadores ([código](https://github.com/Turtlecute33/adblocktest)). Tiene tres partes:

- **Dominios (Host):** intenta conectar con 127 dominios reales de anuncios, analítica, errores,
  redes sociales y fabricantes (OEMs). Es la única parte que un filtro DNS puede pasar.
- **Cosmético** y **scripts de anuncios:** ocultar cajas de anuncios y bloquear `ads.js` / `pagead.js`
  dentro de la página. Necesitan un bloqueador dentro del navegador; un filtro DNS no puede, así que
  la nota global de la web siempre será más baja que el porcentaje de dominios.

Su lista de dominios (`d3host.txt`) tiene licencia CC BY-NC-SA: las pruebas la descargan al
ejecutarse y no se copia en este repositorio.

### Resultados (11/11/2026)

| Modo | Dominios bloqueados |
|---|---|
| Ligero (Hagezi Light) | 88 % |
| **Equilibrado** (Hagezi Pro desde 0.2.0; antes Normal, 90 %) | **96 %** |
| Estricto (Hagezi Pro++ desde 0.2.0) | 98 % |
| Cualquier modo + todas las capas | 100 % |

Emulador API 36, modo Equilibrado, a través del filtro: 96 % (123/127), igual que el informe.
(Con la lista Normal de 0.1 era 90 %, 115/127.)

Lo que deja pasar Equilibrado, y por qué:

- `adservice.google.com`, `ad.doubleclick.net`: Hagezi los permite a propósito en todos sus modos
  porque bloquearlos rompe enlaces de anuncios de Google (Shopping, resultados patrocinados).
- Bugsnag y Sentry (errores de apps): solo los bloquea Estricto. Bloquearlos no quita anuncios y
  puede dificultar que los desarrolladores arreglen fallos.
- `ads.pinterest.com`, Yandex Metrica/AppMetrica/Adfox: solo en Estricto.

Quien quiera el 100 % puede usar Estricto o añadir la capa GoodbyeAds.

## Prueba de carga larga (app auxiliar `loadtest`)

Pequeña app aparte ("Prueba de carga", módulo `loadtest/`, no se publica) para dejar un móvil de
pruebas solo durante horas o días. Cada 20 min despierta el móvil (también en Doze) y, en un servicio
en primer plano:

1. Resuelve 15 dominios normales y 15 de anuncios con el DNS del sistema —que pasa por el filtro— y
   anota batería, VPN activa, aciertos y tiempos en `files/log.csv`.
2. Abre 5 webs reales de una lista variada (`Sites.kt`: medios, deportes, tecnología, recetas, tiempo,
   ocio, compras, herramientas e internacionales) en un navegador interno, acepta el aviso de cookies
   como haría la mayoría de la gente (`--ez cookies false` para no hacerlo) y baja por la página.
   Anota en `files/webs.csv` el tiempo, el número de peticiones y todos los dominios que pidió cada web.

### Registro interno (solo compilaciones de depuración)

Las compilaciones de depuración de la app guardan cada consulta en
`files/debug/consultas-AAAA-MM-DD.csv` (app, dominio, decisión, regla, tiempo de respuesta del DNS
real) y estadísticas cada 5 minutos. En la versión publicada ese código no existe: el compilador lo
elimina. Cruzando ese registro con `webs.csv` se ve, web a web, qué se bloqueó y qué pasó.

```bash
./gradlew :loadtest:assembleDebug
adb install -r loadtest/build/outputs/apk/debug/loadtest-debug.apk
adb shell dumpsys deviceidle whitelist +app.bloqueador.loadtest   # solo la app auxiliar
adb shell am start -n app.bloqueador.loadtest/.MainActivity --es accion iniciar --ei minutos 15
adb shell run-as app.bloqueador.loadtest cat files/log.csv          # leer el registro
adb shell am start -n app.bloqueador.loadtest/.MainActivity --es accion parar
```

Bloqueador no se exime de la optimización de batería, para comprobar que sobrevive con los ajustes
de fábrica del móvil. Se reanuda sola si el móvil se reinicia.
