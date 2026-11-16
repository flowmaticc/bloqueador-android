/**
 * Servicio de reportes de Garbillo (Cloudflare Worker).
 *
 * Recibe desde la app o desde la web un dominio que alguien quiere que se analice y lo apunta como
 * issue en un repositorio privado de GitHub. Si el dominio ya está abierto, añade un comentario
 * (así se ve cuántas veces se ha pedido).
 *
 * Privacidad: solo se guarda el dominio, el motivo, el origen (app o web), la versión de la app y una
 * nota opcional. No se guarda la IP: para limitar abusos se usa un resumen (hash) de la conexión y del
 * día (en IPv6, del bloque /64), que se borra a los dos días.
 *
 * Secretos y variables (wrangler.toml / `wrangler secret put`):
 *   GITHUB_TOKEN  token de GitHub con permiso solo de issues en REPO
 *   REPO          "propietario/repositorio" donde se apuntan los reportes
 *   DB            base de datos D1 (schema.sql): límites de envío y títulos → número de incidencia
 *
 * Si la base de datos falla, se responde 429 (mejor rechazar un envío que dejar pasar una avalancha).
 */

const MOTIVOS = {
  anuncio: "Pasa publicidad o rastreo",
  rota: "Algo deja de funcionar",
  peligro: "Parece peligroso (phishing, estafa…)",
  otro: "Otro",
};
const ORIGENES = ["app", "web"];
const MAX_PER_DAY = 20;
const MAX_TOTAL_PER_DAY = 500; // tope global: protege el repositorio de una avalancha
const MAX_BODY_BYTES = 2048;
const ALLOWED_ORIGINS = ["https://flowmaticc.github.io"];

export default {
  /** Tarea diaria (cron en wrangler.toml): borra los contadores caducados. */
  async scheduled(_evento, env) {
    await env.DB.prepare(SQL_LIMPIAR).bind(ahora()).run();
  },

  async fetch(request, env) {
    const cors = corsHeaders(request);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });

    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") return json({ ok: true, servicio: "reportes de Garbillo" }, 200, cors);
    if (request.method === "POST" && url.pathname === "/error") return recibirInformeDeErrores(request, env, cors);
    if (request.method !== "POST" || url.pathname !== "/reportar") return json({ ok: false, error: "no encontrado" }, 404, cors);

    let data;
    try {
      const texto = await request.text();
      if (texto.length > MAX_BODY_BYTES) return json({ ok: false, error: "datos demasiado grandes" }, 413, cors);
      data = JSON.parse(texto);
    } catch {
      return json({ ok: false, error: "datos no válidos" }, 400, cors);
    }
    if (typeof data !== "object" || data === null || Array.isArray(data)) {
      return json({ ok: false, error: "datos no válidos" }, 400, cors);
    }

    // Campo trampa del formulario web: las personas no lo ven; los robots lo rellenan.
    if (data.web) return json({ ok: true }, 200, cors);

    const dominio = limpiarDominio(String(data.dominio || ""));
    const motivo = Object.hasOwn(MOTIVOS, data.motivo) ? data.motivo : "otro";
    const origen = ORIGENES.includes(data.origen) ? data.origen : "web";
    const version = String(data.version || "").slice(0, 40).replace(/[^\w.\-]/g, "");
    const nota = limpiarNota(data.nota);
    if (!dominio) return json({ ok: false, error: "dominio no válido" }, 400, cors);

    if (!(await dentroDelLimite(request, env))) {
      return json({ ok: false, error: "demasiados reportes hoy desde esta conexión" }, 429, cors);
    }

    try {
      // La nota va en un bloque de código: GitHub no la interpreta (ni menciones, ni enlaces, ni imágenes).
      const linea = `${MOTIVOS[motivo]} · desde la ${origen}${version ? ` (v${version})` : ""}${nota ? `\n\n\`\`\`text\n${nota}\n\`\`\`` : ""}`;
      await apuntar(env, {
        title: dominio,
        body: `**Dominio:** \`${dominio}\`\n\n${linea}`,
        labels: ["reporte", motivo],
        comentario: `+1 · ${linea}`,
      });
    } catch (e) {
      return json({ ok: false, error: `no se pudo guardar el reporte${codigoDeGithub(e)}` }, 502, cors);
    }
    return json({ ok: true }, 200, cors);
  },
};

/** "(GitHub 403)" si el fallo vino de GitHub; nada más (sin detalles internos). */
function codigoDeGithub(e) {
  const m = /^GitHub (\d{3})$/.exec(String(e?.message ?? ""));
  return m ? ` (GitHub ${m[1]})` : "";
}

/** Acepta una URL o un dominio y devuelve el dominio en minúsculas, o "" si no es válido. */
export function limpiarDominio(entrada) {
  let s = String(entrada ?? "").trim().toLowerCase();
  if (!s || s.length > 300) return "";
  try {
    if (s.includes("/") || s.includes(":")) s = new URL(s.includes("://") ? s : `https://${s}`).hostname;
  } catch {
    return "";
  }
  s = s.replace(/^www\./, "").replace(/\.$/, "");
  return /^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/.test(s) ? s : "";
}

/** Texto libre del usuario: sin caracteres de control ni nada que GitHub pueda interpretar. */
export function limpiarNota(entrada) {
  return String(entrada ?? "")
    .replace(/[\u0000-\u001f\u007f<>`]/g, " ")
    .replace(/@/g, "(a)")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 300);
}

// --- Base de datos (D1) ---

/** Suma de forma atómica y devuelve el valor nuevo: no hay carreras entre peticiones simultáneas. */
export const SQL_SUMAR =
  "INSERT INTO limites (clave, n, caduca) VALUES (?1, ?2, ?3) ON CONFLICT (clave) DO UPDATE SET n = n + ?2 RETURNING n";
export const SQL_LIMPIAR = "DELETE FROM limites WHERE caduca < ?1";
export const SQL_ISSUE = "SELECT numero FROM issues WHERE titulo = ?1";
export const SQL_GUARDAR_ISSUE =
  "INSERT INTO issues (titulo, numero) VALUES (?1, ?2) ON CONFLICT (titulo) DO UPDATE SET numero = ?2";

const ahora = () => Math.floor(Date.now() / 1000);
const DOS_DIAS = 2 * 86400;

async function sumar(env, clave, cantidad) {
  const fila = await env.DB.prepare(SQL_SUMAR).bind(clave, cantidad, ahora() + DOS_DIAS).first();
  return Number(fila?.n ?? Infinity);
}

/**
 * Lo que identifica a una conexión para el límite: la IPv4 entera o el bloque /64 de una IPv6 (cada
 * conexión doméstica IPv6 recibe al menos un /64 y puede cambiar de dirección dentro de él a voluntad).
 */
export function conexion(ip) {
  const s = String(ip ?? "").trim().toLowerCase();
  if (!s.includes(":")) return s || "desconocida";
  const [cabeza, cola = ""] = s.split("::");
  const a = cabeza ? cabeza.split(":") : [];
  const b = cola ? cola.split(":") : [];
  const grupos = s.includes("::") ? [...a, ...Array(Math.max(0, 8 - a.length - b.length)).fill("0"), ...b] : a;
  return grupos.slice(0, 4).map((g) => (parseInt(g, 16) || 0).toString(16)).join(":") + "::/64";
}

/**
 * Límite por conexión y día (con un resumen que no permite recuperar la IP) y tope global diario.
 * [cantidad] es lo que cuenta este envío (en los informes, el número de errores que trae).
 * Cualquier fallo de la base de datos cuenta como "fuera del límite".
 */
async function dentroDelLimite(request, env, { prefijo = "", cantidad = 1, maxPorConexion = MAX_PER_DAY, maxTotal = MAX_TOTAL_PER_DAY } = {}) {
  try {
    const dia = new Date().toISOString().slice(0, 10);
    const bytes = new TextEncoder().encode(`${conexion(request.headers.get("CF-Connecting-IP"))}|${dia}|garbillo`);
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
    if ((await sumar(env, `${prefijo}l:${hash.slice(0, 32)}`, cantidad)) > maxPorConexion) return false;
    return (await sumar(env, `${prefijo}t:${dia}`, cantidad)) <= maxTotal;
  } catch {
    return false;
  }
}

/**
 * Crea la incidencia o, si ya hay una con ese título, le añade [comentario]. El número se guarda en D1
 * (la Search API de GitHub tiene un límite muy bajo y tarda en indexar). Si la incidencia guardada ya
 * no existe (borrada o transferida), se crea otra.
 */
async function apuntar(env, { title, body, labels, comentario }) {
  const fila = await env.DB.prepare(SQL_ISSUE).bind(title).first();
  if (fila?.numero) {
    try {
      await github(env, `/repos/${env.REPO}/issues/${fila.numero}/comments`, { body: comentario });
      return;
    } catch (e) {
      if (!/^GitHub (404|410)$/.test(String(e.message))) throw e;
    }
  }
  const creada = await crearIssue(env, { title, body, labels });
  await env.DB.prepare(SQL_GUARDAR_ISSUE).bind(title, creada.number).run();
}

// ---------------------------------------------------------------------------------------------
// Informes de errores anónimos de la app (opcionales; el usuario los activa y puede verlos antes).
// Solo se aceptan campos conocidos con formatos cerrados: nada de texto libre.
// ---------------------------------------------------------------------------------------------

const MAX_INFORME_BYTES = 8192;
const MAX_ERRORES_POR_INFORME = 10; // cada error son hasta 2 llamadas a GitHub
const CANALES = ["stable", "beta", "nightly"];
const NIVELES = ["light", "balanced", "strict"];
const DIA = /^\d{4}-\d{2}-\d{2}$/;

/** Texto corto y seguro: solo letras, números y unos pocos signos (igual que filtra la app). */
export function limpiarCampo(valor, max = 120) {
  return String(valor ?? "").replace(/[^A-Za-z0-9 _.:$/<>-]/g, "").trim().slice(0, max);
}

const entero = (v, min, max) => (Number.isInteger(v) && v >= min && v <= max ? v : null);

/** Valida y normaliza un informe. Devuelve null si no tiene la forma esperada. */
export function limpiarInforme(data) {
  if (typeof data !== "object" || data === null || Array.isArray(data)) return null;
  const android = entero(data.android, 21, 99);
  if (android === null || !Array.isArray(data.errores) || data.errores.length === 0) return null;
  const errores = data.errores.slice(0, MAX_ERRORES_POR_INFORME).map((e) => {
    if (typeof e !== "object" || e === null) return null;
    const tipo = String(e.tipo ?? "").toLowerCase();
    if (!/^[a-z_]{1,40}$/.test(tipo)) return null;
    return {
      tipo,
      detalle: limpiarCampo(e.detalle, 60),
      traza: (Array.isArray(e.traza) ? e.traza : []).slice(0, 12).map((l) => limpiarCampo(l)).filter(Boolean),
      veces: entero(e.veces, 1, 9999) ?? 1,
      primer_dia: DIA.test(e.primer_dia) ? e.primer_dia : "",
      ultimo_dia: DIA.test(e.ultimo_dia) ? e.ultimo_dia : "",
    };
  }).filter(Boolean);
  if (errores.length === 0) return null;
  return {
    version: String(data.version ?? "").slice(0, 40).replace(/[^\w.\-]/g, ""),
    canal: CANALES.includes(data.canal) ? data.canal : "",
    android,
    fabricante: limpiarCampo(data.fabricante, 30).toLowerCase(),
    nivel: NIVELES.includes(data.nivel) ? data.nivel : "",
    listas_extra: entero(data.listas_extra, 0, 50) ?? 0,
    amenazas: data.amenazas === true,
    errores,
  };
}

/** Lo que identifica "el mismo error": tipo, detalle y las primeras líneas de la traza. */
export async function firmaDeError(e) {
  const bytes = new TextEncoder().encode([e.tipo, e.detalle, ...e.traza.slice(0, 4)].join("|"));
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return hash.slice(0, 10);
}

async function recibirInformeDeErrores(request, env, cors) {
  let informe;
  try {
    const texto = await request.text();
    if (texto.length > MAX_INFORME_BYTES) return json({ ok: false, error: "datos demasiado grandes" }, 413, cors);
    informe = limpiarInforme(JSON.parse(texto));
  } catch {
    informe = null;
  }
  if (!informe) return json({ ok: false, error: "datos no válidos" }, 400, cors);

  // Se cuentan errores, no envíos: un informe con 10 errores son hasta 20 llamadas a GitHub.
  const limite = { prefijo: "e", cantidad: informe.errores.length, maxPorConexion: 20, maxTotal: 2000 };
  if (!(await dentroDelLimite(request, env, limite))) {
    return json({ ok: false, error: "demasiados informes hoy desde esta conexión" }, 429, cors);
  }

  const contexto = `v${informe.version || "?"} (${informe.canal || "?"}) · Android API ${informe.android} · ${informe.fabricante || "?"} · nivel ${informe.nivel || "?"}, ${informe.listas_extra} listas extra, amenazas ${informe.amenazas ? "sí" : "no"}`;
  try {
    for (const e of informe.errores) {
      // Todo va en bloques de código: GitHub no interpreta nada de lo recibido.
      const linea = `${e.veces} vez/veces entre ${e.primer_dia || "?"} y ${e.ultimo_dia || "?"}\n\`\`\`text\n${contexto}\n\`\`\``;
      await apuntar(env, {
        title: `Error: ${e.tipo} · ${await firmaDeError(e)}`,
        body: `**Tipo:** \`${e.tipo}\`${e.detalle ? ` · **Detalle:** \`${e.detalle}\`` : ""}\n\n**Traza:**\n\`\`\`text\n${e.traza.join("\n") || "(sin traza)"}\n\`\`\`\n\n${linea}`,
        labels: ["error"],
        comentario: `+ ${linea}`,
      });
    }
  } catch (e) {
    return json({ ok: false, error: `no se pudo guardar el informe${codigoDeGithub(e)}` }, 502, cors);
  }
  return json({ ok: true }, 200, cors);
}

/** Crea la incidencia (si alguna etiqueta no existe aún en el repositorio, la crea sin etiquetas). */
async function crearIssue(env, issue) {
  try {
    return await github(env, `/repos/${env.REPO}/issues`, issue);
  } catch (e) {
    if (String(e.message) !== "GitHub 422") throw e;
    return github(env, `/repos/${env.REPO}/issues`, { title: issue.title, body: issue.body });
  }
}

async function github(env, path, body) {
  const r = await fetch(`https://api.github.com${path}`, {
    method: body ? "POST" : "GET",
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "garbillo-reportes",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(`GitHub ${r.status}`);
  return r.json();
}

function corsHeaders(request) {
  const origin = request.headers.get("Origin");
  return {
    "Access-Control-Allow-Origin": ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0],
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    Vary: "Origin",
  };
}

function json(obj, status, headers) {
  return new Response(JSON.stringify(obj), { status, headers: { ...headers, "Content-Type": "application/json; charset=utf-8" } });
}
