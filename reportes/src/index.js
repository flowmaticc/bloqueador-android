/**
 * Servicio de reportes de Garbillo (Cloudflare Worker).
 *
 * Recibe desde la app o desde la web un dominio que alguien quiere que se analice y lo apunta como
 * issue en un repositorio privado de GitHub. Si el dominio ya está abierto, añade un comentario
 * (así se ve cuántas veces se ha pedido).
 *
 * Privacidad: solo se guarda el dominio, el motivo, el origen (app o web), la versión de la app y una
 * nota opcional. No se guarda la IP: para limitar abusos se usa un resumen (hash) de la IP y del día,
 * que caduca solo a las 24 h.
 *
 * Secretos y variables (wrangler.toml / `wrangler secret put`):
 *   GITHUB_TOKEN  token de GitHub con permiso solo de issues en REPO
 *   REPO          "propietario/repositorio" donde se apuntan los reportes
 *   LIMITS        espacio KV para el límite de envíos
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
      const existente = await buscarIssue(env, dominio);
      // La nota va en un bloque de código: GitHub no la interpreta (ni menciones, ni enlaces, ni imágenes).
      const linea = `${MOTIVOS[motivo]} · desde la ${origen}${version ? ` (v${version})` : ""}${nota ? `\n\n\`\`\`text\n${nota}\n\`\`\`` : ""}`;
      if (existente) {
        await github(env, `/repos/${env.REPO}/issues/${existente}/comments`, { body: `+1 · ${linea}` });
      } else {
        await github(env, `/repos/${env.REPO}/issues`, {
          title: dominio,
          body: `**Dominio:** \`${dominio}\`\n\n${linea}`,
          labels: ["reporte", motivo],
        });
      }
    } catch (e) {
      return json({ ok: false, error: "no se pudo guardar el reporte" }, 502, cors);
    }
    return json({ ok: true }, 200, cors);
  },
};

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

/**
 * Límite de envíos por conexión y día (con un resumen de la IP que no permite recuperarla) y tope global.
 * [prefijo] separa los contadores de reportes y de informes de errores.
 */
async function dentroDelLimite(request, env, { prefijo = "", maxPorConexion = MAX_PER_DAY, maxTotal = MAX_TOTAL_PER_DAY } = {}) {
  const ip = request.headers.get("CF-Connecting-IP") || "desconocida";
  const dia = new Date().toISOString().slice(0, 10);
  const claveTotal = `${prefijo}t:${dia}`;
  const total = parseInt((await env.LIMITS.get(claveTotal)) || "0", 10);
  if (total >= maxTotal) return false;
  const bytes = new TextEncoder().encode(`${ip}|${dia}|garbillo`);
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
  const clave = `${prefijo}l:${hash.slice(0, 32)}`;
  const actual = parseInt((await env.LIMITS.get(clave)) || "0", 10);
  if (actual >= maxPorConexion) return false;
  await env.LIMITS.put(clave, String(actual + 1), { expirationTtl: 86400 });
  await env.LIMITS.put(claveTotal, String(total + 1), { expirationTtl: 172800 });
  return true;
}

async function buscarIssue(env, titulo) {
  const q = encodeURIComponent(`repo:${env.REPO} is:issue is:open in:title "${titulo}"`);
  const r = await github(env, `/search/issues?q=${q}&per_page=10`);
  const hit = (r.items || []).find((i) => i.title === titulo);
  return hit ? hit.number : null;
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

  if (!(await dentroDelLimite(request, env, { prefijo: "e", maxPorConexion: 5, maxTotal: 1000 }))) {
    return json({ ok: false, error: "demasiados informes hoy desde esta conexión" }, 429, cors);
  }

  const contexto = `v${informe.version || "?"} (${informe.canal || "?"}) · Android API ${informe.android} · ${informe.fabricante || "?"} · nivel ${informe.nivel || "?"}, ${informe.listas_extra} listas extra, amenazas ${informe.amenazas ? "sí" : "no"}`;
  try {
    for (const e of informe.errores) {
      const titulo = `Error: ${e.tipo} · ${await firmaDeError(e)}`;
      // Todo va en bloques de código: GitHub no interpreta nada de lo recibido.
      const linea = `${e.veces} vez/veces entre ${e.primer_dia || "?"} y ${e.ultimo_dia || "?"}\n\`\`\`text\n${contexto}\n\`\`\``;
      const existente = await buscarIssue(env, titulo);
      if (existente) {
        await github(env, `/repos/${env.REPO}/issues/${existente}/comments`, { body: `+ ${linea}` });
      } else {
        const cuerpo = `**Tipo:** \`${e.tipo}\`${e.detalle ? ` · **Detalle:** \`${e.detalle}\`` : ""}\n\n**Traza:**\n\`\`\`text\n${e.traza.join("\n") || "(sin traza)"}\n\`\`\`\n\n${linea}`;
        await crearIssue(env, { title: titulo, body: cuerpo, labels: ["error"] });
      }
    }
  } catch {
    return json({ ok: false, error: "no se pudo guardar el informe" }, 502, cors);
  }
  return json({ ok: true }, 200, cors);
}

/** Crea la incidencia; si la etiqueta no existe aún en el repositorio, la crea sin etiqueta. */
async function crearIssue(env, issue) {
  try {
    await github(env, `/repos/${env.REPO}/issues`, issue);
  } catch (e) {
    if (!String(e.message).includes("422")) throw e;
    await github(env, `/repos/${env.REPO}/issues`, { title: issue.title, body: issue.body });
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
