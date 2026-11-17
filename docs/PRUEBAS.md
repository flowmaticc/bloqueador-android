# Pruebas

| Prueba | Qué comprueba | Cómo se lanza |
|---|---|---|
| Tests unitarios | Parser de listas, motor de coincidencias, DNS, historial, actualizador | `gradlew testGithubDebugUnitTest` (también en cada publicación) |
| Prueba de humo | En emulador o móvil: instalación, bienvenida, VPN, bloqueo, resolución normal, historial, apagado | `tools/smoke-test.sh <serial> <carpeta>` |
| Cobertura turtlecute (listas) | Qué % de los dominios de [adblock.turtlecute.org](https://adblock.turtlecute.org) bloquea cada modo y capa | `gradlew testGithubDebugUnitTest --tests '*TurtlecuteCoverageTest' -Pturtlecute` → `app/build/reports/turtlecute.md` |
| Turtlecute en el dispositivo | Lo mismo, pero a través del filtro real del móvil (VPN activa) | `tools/turtlecute-device.sh <serial> [carpeta]` (la prueba de humo ya lo incluye) |
| Cobertura por sitio | Qué % de los dominios de cualquier web (o test) bloquea cada modo y capa | `gradlew :app:testGithubDebugUnitTest --tests '*SiteCoverageTest' -PsiteHosts=<carpeta>` → `app/build/reports/sites.md`. Cada fichero: dominios separados por espacios, o `categoría<TAB>dominio`. |
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

## Resultados en móviles reales

| Móvil | Android | Uso |
|---|---|---|
| Samsung Galaxy A7 (2018), batería de 3300 mAh | 10 | Móvil de pruebas, solo con wifi |
| Google Pixel 10 | 17 | Uso diario normal |
| Xiaomi Redmi Note 10S (MIUI 14) | 13 | Móvil de pruebas, solo con wifi |
| Emulador | 16 (API 36) | Prueba de humo de cada cambio |

### 24 horas con carga (Galaxy A7, versión 0.1.0, noviembre de 2026)

Sin cable durante 24 h 30 min; cada 15 minutos, 15 consultas normales, 15 de publicidad y 3 webs reales.

- 100 de 100 ciclos correctos, también de noche: filtro activo en todos, 1500/1500 consultas normales
  resueltas y 1500/1500 de publicidad bloqueadas. Respuesta DNS media: 26 ms.
- Consumo de Garbillo según Android: **4 mAh en 24 h** (≈0,1 % de la batería). Lo demás fue la propia
  prueba, la radio y el reposo del móvil.
- Una actualización desde la app a mitad de prueba cortó el filtro unos 5 segundos y volvió solo.

### 46 horas de navegación intensiva (Galaxy A7, versión 0.3.0, noviembre de 2026)

Sin cable durante 46 h; cada 20 minutos, comprobaciones DNS y 5 webs reales de una lista de 72
(medios, deportes, tecnología, recetas, tiempo, ocio, compras, herramientas e internacionales), con el
aviso de cookies aceptado y bajando por la página. Modo Equilibrado + protección contra amenazas.

| Medida | Resultado |
|---|---|
| Consultas DNS filtradas | 28 490, de las que el 47 % se bloquearon |
| Consultas reenviadas sin respuesta | 1 de 15 084 (0,01 %) |
| Tiempo de respuesta del DNS real | mediana 7 ms · 90 % por debajo de 43 ms · 99 % por debajo de 173 ms |
| Ciclos de comprobación | 111 de 111 correctos, sin cortes del filtro |
| Dominios publicitarios conocidos en 360 cargas de webs | 601 de 648 bloqueados (92,7 %) |
| Consumo propio de Garbillo | 8 mAh en 46 h (≈0,25 % de la batería) |

Los dominios publicitarios que pasaron (redirecciones de clics de anuncios y enlaces de compra) los deja
pasar la lista Hagezi Pro a propósito, porque bloquearlos rompe enlaces. El nivel Estricto bloquea más.

### Uso diario real (Pixel 10, versión 0.1.0, noviembre de 2026)

- 13 horas de uso normal: el trabajo de fondo del filtro gastó **7 mAh** (≈0,15 % de la batería);
  con la pantalla apagada, 1,5 mAh en total.
- El filtro siguió activo todo el tiempo, también tras reiniciar el móvil.

### Pruebas de funcionamiento (Galaxy A7, versiones 0.3.0 y 0.3.1, 15/11/2026)

| Prueba | Resultado |
|---|---|
| Publicidad y rastreo (12 dominios conocidos) | 11 bloqueados; el otro lo deja pasar la lista a propósito |
| DNS cifrados que se saltarían el filtro (Google, Cloudflare, Quad9, Firefox) | 5 de 5 bloqueados |
| Phishing real tomado de la lista de amenazas | 4 de 4 bloqueados y marcados como peligro |
| Webs y servicios normales | 10 de 10 funcionan |
| IPv6 | Se bloquea igual que IPv4 |
| Consulta nueva (sin caché) | 70 ms de media |
| Actualizar encima de la versión anterior | Conserva los datos; el filtro vuelve solo en 5 s |
| Actualizar desde la propia app (Android 10) | Correcto con la 0.3.1 |
| Reiniciar el móvil | El filtro se activa solo |
| Apagar y encender el filtro | Correcto |
| Quedarse sin red y recuperarla | Sin red sigue bloqueando; al volver la wifi, todo funciona en 4 s |

### Actualizar desde una versión antigua y restricciones de Xiaomi (Redmi Note 10S, 15/11/2026)

Instalación limpia de la 0.2.0, configurada con las seis listas adicionales, y actualizaciones desde la
propia app a la 0.3.1 y a la versión de desarrollo siguiente.

| Prueba | Resultado |
|---|---|
| Actualizar desde la app (0.2.0 → 0.3.1 → desarrollo) | Correcto; se conservan nivel, listas, apps protegidas e historial |
| Reactivarse solo tras actualizar | MIUI lo impide si la app no tiene "Inicio automático" |
| Abrir la app con el filtro apagado | El filtro vuelve a activarse solo |
| Aviso de "Inicio automático" | Aparece en la pantalla principal y su botón abre los ajustes de Xiaomi |
| Cambiar a nivel Estricto sin conexión | Vuelve al nivel anterior, avisa y el filtro sigue bloqueando con su lista |
| Reiniciar con "Inicio automático" permitido | El filtro se activa solo a los 31 s del arranque |

### Registro interno (solo compilaciones de depuración)

Las compilaciones de depuración guardan en el propio móvil cada consulta
(`files/debug/consultas-AAAA-MM-DD.csv`: app, dominio, decisión, regla y tiempo de respuesta) para
analizar las pruebas. **En la versión publicada ese código no existe**: el compilador lo elimina, y
nada de ese registro sale del móvil.
