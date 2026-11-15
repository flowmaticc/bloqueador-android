# Novedades

## Próxima versión

- **Bienvenida nueva**: ayuda a dejar listo lo que hace que el filtro funcione siempre (notificaciones,
  batería e inicio automático) y te deja elegir cómo descargar y si quieres enviar informes de errores.
- **Descargar solo con wifi** (activado por defecto): las listas y las comprobaciones de versiones se
  descargan solas en cuanto te conectas a una wifi. Si pulsas "Actualizar ahora" con datos móviles, te
  lo pregunta antes.
- **Informes de errores anónimos, opcionales**: si los activas, cuando algo falla se envía un aviso sin
  webs, historial, apps ni identificadores. Puedes ver el informe exacto antes de que salga en
  Ajustes → Informes de errores.
- Al actualizar desde la app, se explica qué hacer si Google Play Protect bloquea la instalación.
- Las versiones de desarrollo (Nightly) se numeran siempre por delante de la última estable, para que
  no parezca que una actualización baja de versión.

## 0.3.2

- **Xiaomi, Huawei, OPPO y Vivo**: un aviso explica cómo dar el permiso de "Inicio automático", sin el
  cual el sistema no deja que el filtro se vuelva a activar solo tras reiniciar o actualizar.
- Si el filtro estaba activado y lo encuentras apagado, se vuelve a activar al abrir la app.
- Corregido: si una lista no se puede descargar (por ejemplo, sin conexión), el ajuste vuelve a como
  estaba y se avisa, en vez de quedarse marcado sin funcionar.
- Corregido: cambiar de nivel de bloqueo sin conexión podía dejar el móvil sin ninguna lista; ahora la
  lista anterior se conserva hasta que la nueva se descarga bien.
- Las novedades de cada actualización se ven completas, sin cortes a mitad de frase.

## 0.3.1

- **Corregido: en Android 9 y 10 las actualizaciones desde la app fallaban** con el aviso "La descarga
  no es de Garbillo". Si tienes la 0.3.0 en uno de esos Android, instala esta versión una vez a mano
  desde la web; a partir de ahí se actualiza sola.
- Corregido: en Ajustes, una sección abierta desde el buscador podía verse cortada.
- **Historial más privado**: al borrarlo no quedan restos en el archivo del móvil, y lo antiguo se
  borra a su tiempo aunque el filtro lleve días apagado.

## 0.3.0

- **Nuevo nombre: Garbillo**, con icono propio. Se actualiza encima de la versión que tengas.
- **Protección contra amenazas**, activada de serie: bloquea webs de phishing, malware y estafas, avisa
  cuando lo hace y tiene su propia sección en Ajustes.
- **Reportar una web**: desde el historial puedes enviar un dominio para que se analice (anuncios que
  pasan, algo que deja de funcionar o una web peligrosa).
- **Estado real de la protección**: la pantalla principal avisa si no hay listas cargadas, si el DNS
  de tu red no responde o si el DNS privado de Android se salta el filtro.
- **Se activa solo al encender el móvil** si lo tenías activado, sin tener que abrir la app.
- **Sin anuncios al arrancar**: el filtro carga las listas antes de empezar a filtrar.
- Si el DNS de tu red deja de responder, se usa otro automáticamente.
- Más protección frente a respuestas DNS falsas en redes wifi públicas.
- Las respuestas DNS grandes llegan completas.
- Las actualizaciones de la app solo se instalan si vienen de GitHub y están firmadas con la misma
  clave que la app instalada.
- "Ver en la web" nunca abre sitios marcados como peligrosos.
- Ajustes: guía para dejar la VPN siempre activa y enlaces a la web y a la política de privacidad.
- Corregido: al añadir una excepción, un dominio podía seguir bloqueado unos minutos.

## 0.2.0

- **Bloquea más por defecto.** El nivel Equilibrado usa la lista Hagezi Pro y el Estricto la Pro++.
- **Anti-evasión DNS activada de serie**, para que navegadores y apps no se salten el filtro.
- **Lista de tu fabricante**: en móviles Samsung, Xiaomi, Oppo/Realme/OnePlus, Huawei/Honor, Vivo y
  Amazon se bloquea también la publicidad y el rastreo del propio sistema.
- **Historial: "Ver en la web"**: carga la web donde apareció un dominio y rodea en rojo lo que pone.
- **Ajustes ordenados en categorías, con buscador.**
- **Se retira el bloqueo visual**: ya no se piden permisos de accesibilidad ni de captura de pantalla.
- Avisos claros de lo que un bloqueador de este tipo no puede quitar.

## 0.1.0

- Primera versión: filtro DNS local sin root, listas Hagezi y capas extra, elección de apps,
  actualización automática de listas, historial y actualizaciones de la app desde GitHub.
