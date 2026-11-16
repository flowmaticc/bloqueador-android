-- Base de datos D1 del servicio de reportes.
-- Crear:    npx wrangler d1 create garbillo-reportes   (y copiar el id a wrangler.toml)
-- Aplicar:  npx wrangler d1 execute garbillo-reportes --remote --file schema.sql

-- Contadores de envíos por conexión y día (la clave es un resumen de la IP, nunca la IP) y totales.
CREATE TABLE IF NOT EXISTS limites (
  clave  TEXT PRIMARY KEY,
  n      INTEGER NOT NULL,
  caduca INTEGER NOT NULL -- segundos Unix; la tarea diaria borra lo caducado
);

-- Título de la incidencia (dominio o firma de error) → número en GitHub. Sustituye a la Search API.
CREATE TABLE IF NOT EXISTS issues (
  titulo TEXT PRIMARY KEY,
  numero INTEGER NOT NULL
);
