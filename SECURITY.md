# Seguridad

Garbillo filtra el DNS de todo el móvil, así que un fallo de seguridad puede afectar a todas las apps.
Gracias por avisar con cuidado.

## Cómo avisar de un fallo

**No abras una incidencia pública.** Usa el aviso privado de GitHub:
[Security → Report a vulnerability](https://github.com/flowmaticc/bloqueador-android/security/advisories/new).
Solo lo ven los responsables del proyecto.

Incluye, si puedes: versión de Garbillo y de Android, qué pasa, cómo reproducirlo y qué impacto crees
que tiene. Se puede escribir en español o en inglés.

## Qué esperar

- Respuesta en una semana como mucho.
- Si se confirma, se corrige en una versión estable propia (sin esperar al ritmo normal de versiones)
  y la app avisa a quien tenga una versión afectada para que actualice.
- Se te mencionará en las notas de la versión, si quieres.

## Qué cuenta como fallo de seguridad

- Saltarse el filtro desde otra app sin que el usuario lo sepa.
- Suplantar respuestas DNS, envenenar el DNS cifrado o leer las consultas desde fuera del móvil.
- Que el actualizador instale algo que no sea un APK oficial firmado con la clave de Garbillo
  (huella en el [README](README.md#instalar)).
- Fugas de datos: historial, dominios visitados o datos del móvil que salgan sin consentimiento.
- Fallos en el servicio de reportes ([`reportes/`](reportes/)) que expongan datos o permitan abusar de él.

No son fallos de seguridad: anuncios que no se bloquean o webs que se rompen. Para eso, reporta el
dominio desde la app o la [web](https://flowmaticc.github.io/utilities/bloqueador-android/#reportar).

## Versiones con soporte

Solo la última versión estable y la beta actual reciben correcciones.
