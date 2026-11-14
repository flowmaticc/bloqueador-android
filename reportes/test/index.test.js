// Tests del servicio de reportes. Ejecutar desde reportes/: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { limpiarDominio, limpiarNota } from "../src/index.js";

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
