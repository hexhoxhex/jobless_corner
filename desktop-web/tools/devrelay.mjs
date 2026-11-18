// The relay for development in an ordinary browser (`bun run relay`).
// Same protocol as src-tauri/src/relay.rs:
//   GET|POST /r?s=<secret>&u=<url>&h=<base64url JSON headers>
// Forwards the request with those headers (and the caller's Range), streams
// the answer back with CORS headers, the final URL in x-final-url and any
// Set-Cookie values as base64url JSON in x-set-cookie.
import http from "node:http";
import { Readable } from "node:stream";

const PORT = Number(process.env.RELAY_PORT || 8797);
const SECRET = process.env.RELAY_SECRET || "dev";
const PASS = ["content-type", "content-length", "content-range", "accept-ranges", "retry-after",
  "cache-control", "last-modified", "etag"];

function cors(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS");
  res.setHeader("Access-Control-Expose-Headers", "*");
  res.setHeader("Access-Control-Allow-Private-Network", "true");
}

function decodeHeaders(h) {
  if (!h) return {};
  try {
    return JSON.parse(Buffer.from(h, "base64url").toString("utf8"));
  } catch {
    return {};
  }
}

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return chunks.length ? Buffer.concat(chunks) : undefined;
}

const server = http.createServer(async (req, res) => {
  cors(res);
  if (req.method === "OPTIONS") {
    res.writeHead(204).end();
    return;
  }
  const q = new URL(req.url, "http://relay").searchParams;
  if (!req.url.startsWith("/r?") || q.get("s") !== SECRET) {
    res.writeHead(403).end("forbidden");
    return;
  }
  const target = q.get("u");
  if (!target || !/^https?:\/\//.test(target)) {
    res.writeHead(400).end("bad url");
    return;
  }
  const headers = decodeHeaders(q.get("h"));
  if (req.headers.range && !headers.Range && !headers.range) headers.Range = req.headers.range;
  const ac = new AbortController();
  const headerTimer = setTimeout(() => ac.abort(), 20_000);
  res.on("close", () => ac.abort());
  try {
    const body = req.method === "POST" ? await readBody(req) : undefined;
    const up = await fetch(target, {
      method: req.method === "HEAD" ? "HEAD" : req.method,
      headers,
      body,
      redirect: "follow",
      signal: ac.signal,
    });
    clearTimeout(headerTimer);
    const encoded = up.headers.get("content-encoding");
    for (const k of PASS) {
      if (k === "content-length" && encoded) continue; // fetch already decoded it
      const v = up.headers.get(k);
      if (v) res.setHeader(k, v);
    }
    const cookies = up.headers.getSetCookie?.() || [];
    if (cookies.length) res.setHeader("x-set-cookie", Buffer.from(JSON.stringify(cookies)).toString("base64url"));
    res.setHeader("x-final-url", up.url);
    res.writeHead(up.status);
    if (!up.body || req.method === "HEAD") {
      res.end();
      return;
    }
    Readable.fromWeb(up.body).on("error", () => res.destroy()).pipe(res);
  } catch (e) {
    clearTimeout(headerTimer);
    if (!res.headersSent) {
      res.setHeader("x-relay-error", String(e?.cause?.code || e?.name || e).slice(0, 200));
      res.writeHead(502).end("relay: upstream failed");
    } else {
      res.destroy();
    }
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`dev relay on http://127.0.0.1:${PORT} (secret "${SECRET}")`);
});
