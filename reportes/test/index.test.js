// Tests del servicio de reportes. Ejecutar desde reportes/: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { limpiarDominio, limpiarNota, limpiarInforme, limpiarCampo, firmaDeError } from "../src/index.js";

/** Entorno falso: almacén KV en memoria y GitHub simulado (se sustituye fetch). */
function entorno({ issuesAbiertas = [] } = {}) {
  const kv = new Map();
  const llamadas = [];
  globalThis.fetch = async (url, init = {}) => {
    llamadas.push({ url: String(url), method: init.method || "GET", body: init.body ? JSON.parse(init.body) : null });
    if (String(url).includes("/search/issues")) return Response.json({ items: issuesAbiertas });
    return Response.json({ number: 99 });
  };
  const env = {
    REPO: "org/repo",
    GITHUB_TOKEN: "x",
    LIMITS: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v) },
  };
  return { env, llamadas, kv };
}

const post = (body, ip = "203.0.113.7") =>
  new Request("https://servicio/reportar", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip, Origin: "https://flowmaticc.github.io" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

test("limpiarDominio acepta dominios y URLs y rechaza lo demás", () => {
  assert.equal(limpiarDominio("https://WWW.Ejemplo.com/ruta?x=1"), "ejemplo.com");
  assert.equal(limpiarDominio("sub.ejemplo.co.uk."), "sub.ejemplo.co.uk");
  assert.equal(limpiarDominio("no es un dominio"), "");
  assert.equal(limpiarDominio("localhost"), "");
  assert.equal(limpiarDominio("ejemplo.com\" onload=x"), "");
  assert.equal(limpiarDominio("a".repeat(400) + ".com"), "");
  assert.equal(limpiarDominio(null), "");
  assert.equal(limpiarDominio("javascript:alert(1)"), "");
});

test("limpiarNota neutraliza menciones, HTML, código y saltos de línea", () => {
  const n = limpiarNota("hola @alguien <img src=x>\n```\n# título\n[enlace](http://x)");
  assert.ok(!n.includes("@"));
  assert.ok(!n.includes("<") && !n.includes(">") && !n.includes("`") && !n.includes("\n"));
  assert.equal(limpiarNota("x".repeat(1000)).length, 300);
  assert.equal(limpiarNota(undefined), "");
});

test("un reporte válido crea un issue con el dominio como título", async () => {
  const { env, llamadas } = entorno();
  const r = await worker.fetch(post({ dominio: "https://ads.ejemplo.com/x", motivo: "anuncio", origen: "app", version: "0.3.0", nota: "hola @todos" }), env);
  assert.equal(r.status, 200);
  const crear = llamadas.find((l) => l.method === "POST" && l.url.endsWith("/repos/org/repo/issues"));
  assert.equal(crear.body.title, "ads.ejemplo.com");
  assert.deepEqual(crear.body.labels, ["reporte", "anuncio"]);
  assert.ok(crear.body.body.includes("```text\nhola (a)todos\n```"));
});

test("si el dominio ya está abierto se añade un comentario en vez de duplicarlo", async () => {
  const { env, llamadas } = entorno({ issuesAbiertas: [{ title: "ads.ejemplo.com", number: 7 }] });
  await worker.fetch(post({ dominio: "ads.ejemplo.com", motivo: "rota", origen: "web" }), env);
  assert.ok(llamadas.some((l) => l.url.endsWith("/issues/7/comments")));
  assert.ok(!llamadas.some((l) => l.method === "POST" && l.url.endsWith("/repos/org/repo/issues")));
});

test("rechaza dominios no válidos, cuerpos enormes y JSON roto sin llamar a GitHub", async () => {
  const { env, llamadas } = entorno();
  assert.equal((await worker.fetch(post({ dominio: "nada" }), env)).status, 400);
  assert.equal((await worker.fetch(post("{roto"), env)).status, 400);
  assert.equal((await worker.fetch(post("[1,2]"), env)).status, 400);
  assert.equal((await worker.fetch(post({ dominio: "ejemplo.com", nota: "x".repeat(5000) }), env)).status, 413);
  assert.equal(llamadas.length, 0);
});

test("motivo y origen desconocidos caen en valores seguros", async () => {
  const { env, llamadas } = entorno();
  await worker.fetch(post({ dominio: "ejemplo.com", motivo: "__proto__", origen: "<script>" }), env);
  const crear = llamadas.find((l) => l.method === "POST" && l.url.endsWith("/issues"));
  assert.deepEqual(crear.body.labels, ["reporte", "otro"]);
  assert.ok(crear.body.body.includes("desde la web"));
});

test("el campo trampa del formulario descarta el envío sin guardar nada", async () => {
  const { env, llamadas } = entorno();
  const r = await worker.fetch(post({ dominio: "ejemplo.com", web: "soy un robot" }), env);
  assert.equal(r.status, 200);
  assert.equal(llamadas.length, 0);
});

test("límite de 20 reportes por conexión y día, sin guardar la IP", async () => {
  const { env, kv } = entorno();
  for (let i = 0; i < 20; i++) assert.equal((await worker.fetch(post({ dominio: `d${i}.ejemplo.com` }), env)).status, 200);
  assert.equal((await worker.fetch(post({ dominio: "otro.ejemplo.com" }), env)).status, 429);
  // Otra conexión sí puede.
  assert.equal((await worker.fetch(post({ dominio: "otro.ejemplo.com" }, "198.51.100.9"), env)).status, 200);
  assert.ok(![...kv.keys()].some((k) => k.includes("203.0.113.7")));
});

test("solo acepta POST /reportar y responde a la comprobación de CORS", async () => {
  const { env } = entorno();
  assert.equal((await worker.fetch(new Request("https://servicio/otra", { method: "POST" }), env)).status, 404);
  const pre = await worker.fetch(new Request("https://servicio/reportar", { method: "OPTIONS", headers: { Origin: "https://malo.example" } }), env);
  assert.equal(pre.status, 204);
  assert.equal(pre.headers.get("Access-Control-Allow-Origin"), "https://flowmaticc.github.io");
});

test("si GitHub falla, responde 502 sin detalles internos", async () => {
  const { env } = entorno();
  globalThis.fetch = async () => new Response("secreto interno", { status: 500 });
  const r = await worker.fetch(post({ dominio: "ejemplo.com" }), env);
  assert.equal(r.status, 502);
  assert.ok(!(await r.text()).includes("secreto"));
});

// --- Informes de errores anónimos ---

const informe = (extra = {}) => ({
  version: "0.3.3-nightly.20261115.abc1234",
  canal: "nightly",
  android: 33,
  fabricante: "xiaomi",
  nivel: "balanced",
  listas_extra: 6,
  amenazas: true,
  errores: [{ tipo: "listas_descarga", detalle: "HTTP 404", traza: ["clase java.io.IOException", "lists.BlocklistRepository.download:120"], veces: 3, primer_dia: "2026-11-14", ultimo_dia: "2026-11-15" }],
  ...extra,
});

const postError = (body, ip = "203.0.113.7") =>
  new Request("https://servicio/error", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

test("limpiarInforme solo deja campos conocidos con formato cerrado", () => {
  const i = limpiarInforme(informe({ email: "ana@example.com", canal: "otro", nivel: "<script>" }));
  assert.equal(i.email, undefined);
  assert.equal(i.canal, "");
  assert.equal(i.nivel, "");
  assert.equal(limpiarInforme(informe({ android: "33" })), null, "android debe ser un número");
  assert.equal(limpiarInforme(informe({ errores: [] })), null);
  assert.equal(limpiarInforme(informe({ errores: [{ tipo: "Tipo Raro!" }] })), null);
  assert.equal(limpiarCampo('a"b`c<d>e\n@f'), "abc<d>ef");
  const muchos = limpiarInforme(informe({ errores: Array.from({ length: 50 }, (_, n) => ({ tipo: "cierre", detalle: `d${n}` })) }));
  assert.equal(muchos.errores.length, 10);
});

test("la firma agrupa el mismo error aunque cambien la versión o las veces", async () => {
  const a = limpiarInforme(informe()).errores[0];
  const b = limpiarInforme(informe({ version: "0.4.0" })).errores[0];
  assert.equal(await firmaDeError(a), await firmaDeError({ ...b, veces: 99 }));
  assert.notEqual(await firmaDeError(a), await firmaDeError({ ...a, detalle: "HTTP 500" }));
});

test("un informe nuevo crea una incidencia con etiqueta error y todo en bloques de código", async () => {
  const { env, llamadas } = entorno();
  const r = await worker.fetch(postError(informe()), env);
  assert.equal(r.status, 200);
  const crear = llamadas.find((l) => l.method === "POST" && l.url.endsWith("/repos/org/repo/issues"));
  assert.match(crear.body.title, /^Error: listas_descarga · [0-9a-f]{10}$/);
  assert.deepEqual(crear.body.labels, ["error"]);
  assert.ok(crear.body.body.includes("```text\nclase java.io.IOException\nlists.BlocklistRepository.download:120\n```"));
  assert.ok(crear.body.body.includes("Android API 33 · xiaomi"));
});

test("si el error ya está abierto se añade un comentario", async () => {
  const titulo = `Error: listas_descarga · ${await firmaDeError(limpiarInforme(informe()).errores[0])}`;
  const { env, llamadas } = entorno({ issuesAbiertas: [{ title: titulo, number: 12 }] });
  await worker.fetch(postError(informe()), env);
  assert.ok(llamadas.some((l) => l.url.endsWith("/issues/12/comments")));
  assert.ok(!llamadas.some((l) => l.method === "POST" && l.url.endsWith("/repos/org/repo/issues")));
});

test("informes no válidos, enormes o en exceso no llegan a GitHub", async () => {
  const { env, llamadas } = entorno();
  assert.equal((await worker.fetch(postError("{roto"), env)).status, 400);
  assert.equal((await worker.fetch(postError(informe({ android: "x" })), env)).status, 400);
  assert.equal((await worker.fetch(postError(JSON.stringify(informe({ relleno: "x".repeat(9000) }))), env)).status, 413);
  assert.equal(llamadas.length, 0);
  for (let i = 0; i < 5; i++) assert.equal((await worker.fetch(postError(informe(), "198.51.100.1"), env)).status, 200);
  assert.equal((await worker.fetch(postError(informe(), "198.51.100.1"), env)).status, 429);
});
