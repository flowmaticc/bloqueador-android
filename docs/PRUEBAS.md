# Pruebas

| Prueba | Qué comprueba | Cómo se lanza |
|---|---|---|
| Tests unitarios | Parser de listas, motor de coincidencias, DNS, historial, actualizador | `gradlew testDebugUnitTest` (también en cada publicación) |
| Prueba de humo | En emulador o móvil: instalación, bienvenida, VPN, bloqueo, resolución normal, historial, apagado | `tools/smoke-test.sh <serial> <carpeta>` |
| Cobertura turtlecute (listas) | Qué % de los dominios de [adblock.turtlecute.org](https://adblock.turtlecute.org) bloquea cada modo y capa | `gradlew testDebugUnitTest --tests '*TurtlecuteCoverageTest' -Pturtlecute` → `app/build/reports/turtlecute.md` |
| Turtlecute en el dispositivo | Lo mismo, pero a través del filtro real del móvil (VPN activa) | `tools/turtlecute-device.sh <serial> [carpeta]` (la prueba de humo ya lo incluye) |
| Cobertura por sitio | Qué % de los dominios de cualquier web (o test) bloquea cada modo y capa | `gradlew :app:testDebugUnitTest --tests '*SiteCoverageTest' -PsiteHosts=<carpeta>` → `app/build/reports/sites.md`. Cada fichero: dominios separados por espacios, o `categoría<TAB>dominio`. |
| Prueba de carga larga | Que el filtro aguanta días sin tocar el móvil, también en reposo profundo | Herramienta privada (ver abajo) |

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

## Prueba de carga larga

Se hace con una herramienta privada de pruebas que no forma parte de este repositorio y **nunca se
publica**: ni ella ni las compilaciones de depuración de Garbillo que se usan con ella. Deja un móvil
de pruebas solo durante días: cada 20 min comprueba que el filtro responde y abre webs reales.
Garbillo no se exime de la optimización de batería, para comprobar que sobrevive con los ajustes de
fábrica del móvil.

### Registro interno (solo compilaciones de depuración)

Las compilaciones de depuración guardan en el propio móvil cada consulta
(`files/debug/consultas-AAAA-MM-DD.csv`: app, dominio, decisión, regla y tiempo de respuesta) para
analizar las pruebas. **En la versión publicada ese código no existe**: el compilador lo elimina, y
nada de ese registro sale del móvil.
