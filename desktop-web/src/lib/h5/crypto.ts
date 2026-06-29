/**
 * Byte-for-byte port of `:shared/net/Crypto.kt`. Uses crypto-js (pure
 * JS HMAC-MD5 — Web Crypto deprecated MD5) so it runs identically in
 * the Tauri WebView, a regular browser, and a Node test runner.
 *
 *   token  -> "<ts>,<md5(reverse(ts))>"
 *   x-tr   -> "<ts>|2|<base64(hmac-md5(canonical, key))>"
 */
import CryptoJS from "crypto-js";
import { SECRET_KEY_DEFAULT } from "./constants";

const SIGNATURE_BODY_MAX_BYTES = 102_400;

function md5Hex(input: string): string {
  return CryptoJS.MD5(input).toString(CryptoJS.enc.Hex);
}

/** token = "<ts>,<md5(reverse(<ts>))>" */
export function clientToken(timestampMs: number): string {
  const ts = String(timestampMs);
  const reversed = ts.split("").reverse().join("");
  return `${ts},${md5Hex(reversed)}`;
}

/** Rebuild the query string with keys sorted; values are NOT re-encoded. */
function sortedQuery(url: string): string {
  const q = url.indexOf("?");
  if (q < 0) return "";
  const query = url.slice(q + 1);
  if (!query) return "";
  const map = new Map<string, string[]>();
  for (const pair of query.split("&")) {
    const eq = pair.indexOf("=");
    const key = eq >= 0 ? pair.slice(0, eq) : pair;
    const value = eq >= 0 ? pair.slice(eq + 1) : "";
    const arr = map.get(key) ?? [];
    arr.push(value);
    map.set(key, arr);
  }
  const out: string[] = [];
  for (const key of [...map.keys()].sort()) {
    for (const v of map.get(key)!) out.push(`${key}=${v}`);
  }
  return out.join("&");
}

function pathOf(url: string): string {
  const schemeEnd = url.indexOf("://");
  const start = schemeEnd >= 0 ? schemeEnd + 3 : 0;
  const slash = url.indexOf("/", start);
  if (slash < 0) return "";
  const q = url.indexOf("?", slash);
  return q < 0 ? url.slice(slash) : url.slice(slash, q);
}

function canonicalString(
  method: string,
  accept: string | null,
  contentType: string | null,
  url: string,
  body: string | null,
  timestampMs: number,
): string {
  const p = pathOf(url);
  const query = sortedQuery(url);
  const canonicalUrl = query ? `${p}?${query}` : p;

  let bodyHash = "";
  let bodyLength = "";
  if (body !== null) {
    // Truncate to the same cap the Android client uses.
    const truncated =
      body.length > SIGNATURE_BODY_MAX_BYTES
        ? body.slice(0, SIGNATURE_BODY_MAX_BYTES)
        : body;
    bodyHash = md5Hex(truncated);
    bodyLength = String(new TextEncoder().encode(body).length);
  }

  return [
    method.toUpperCase(),
    accept ?? "",
    contentType ?? "",
    bodyLength,
    String(timestampMs),
    bodyHash,
    canonicalUrl,
  ].join("\n");
}

/** Returns the `x-tr-signature` header value. */
export function trSignature(
  method: string,
  accept: string | null,
  contentType: string | null,
  url: string,
  body: string | null,
  timestampMs: number,
  secretBase64: string = SECRET_KEY_DEFAULT,
): string {
  const canonical = canonicalString(method, accept, contentType, url, body, timestampMs);
  const key = CryptoJS.enc.Base64.parse(secretBase64);
  const msg = CryptoJS.enc.Utf8.parse(canonical);
  const mac = CryptoJS.HmacMD5(msg, key);
  const b64 = CryptoJS.enc.Base64.stringify(mac);
  return `${timestampMs}|2|${b64}`;
}
