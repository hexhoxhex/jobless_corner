//! The relay: a local HTTP forwarder the page uses for every source request.
//!
//! The page can't set Referer, Origin or User-Agent, is bound by CORS, and a
//! `<video>` can't send headers at all; the stream sources need all three. So
//! the page asks
//!   GET|POST http://127.0.0.1:<port>/r?s=<secret>&u=<url>&h=<base64url JSON headers>
//! and this forwards the request with those headers (plus the caller's Range),
//! streaming the answer back with CORS headers. The final URL after redirects
//! comes back in `x-final-url`, Set-Cookie values in `x-set-cookie` (base64url
//! JSON array). The port is random and every request must carry the secret,
//! so no other program on the machine can use it.
//!
//! Same protocol as tools/devrelay.mjs, which does this job in development.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use axum::body::{Body, Bytes};
use axum::extract::{Query, State};
use axum::http::{header, HeaderMap, HeaderName, HeaderValue, Method, StatusCode};
use axum::response::Response;
use axum::routing::any;
use axum::Router;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::Serialize;
use tauri::AppHandle;

#[derive(Clone, Serialize)]
pub struct RelayInfo {
    pub port: u16,
    pub secret: String,
}

struct Ctx {
    client: reqwest::Client,
    secret: String,
    app: AppHandle,
}

// Bytes pass through untouched: no decompression here (a compressed partial
// file can't be decompressed, and the page decodes whatever it asked for).
const PASS: [&str; 9] = [
    "content-type",
    "content-encoding",
    "content-length",
    "content-range",
    "accept-ranges",
    "retry-after",
    "cache-control",
    "last-modified",
    "etag",
];

/// Bind 127.0.0.1 on a free port and serve in the background. [engine]
/// (debug builds only, see lib.rs): the fixed development port and secret
/// the UI in a browser expects.
pub async fn start(app: AppHandle, engine: bool) -> RelayInfo {
    let mut bytes = [0u8; 24];
    getrandom::getrandom(&mut bytes).expect("random secret");
    let mut secret = URL_SAFE_NO_PAD.encode(bytes);
    let mut bind = "127.0.0.1:0".to_string();
    if engine && cfg!(debug_assertions) {
        bind = "127.0.0.1:8797".into();
        secret = "dev".into();
    }
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .pool_idle_timeout(Duration::from_secs(60))
        .redirect(reqwest::redirect::Policy::limited(10))
        .build()
        .expect("http client");
    let ctx = Arc::new(Ctx { client, secret: secret.clone(), app });
    let app = Router::new()
        .route("/r", any(relay))
        .route("/capture", any(capture_route))
        .with_state(ctx);
    let listener = tokio::net::TcpListener::bind(bind.as_str()).await.expect("bind relay");
    let port = listener.local_addr().expect("relay addr").port();
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    RelayInfo { port, secret }
}

fn cors(h: &mut HeaderMap) {
    h.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, HeaderValue::from_static("*"));
    h.insert(header::ACCESS_CONTROL_ALLOW_HEADERS, HeaderValue::from_static("*"));
    h.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET, POST, HEAD, OPTIONS"),
    );
    h.insert(header::ACCESS_CONTROL_EXPOSE_HEADERS, HeaderValue::from_static("*"));
    h.insert(
        HeaderName::from_static("access-control-allow-private-network"),
        HeaderValue::from_static("true"),
    );
}

fn plain(status: StatusCode, text: &'static str, note: Option<String>) -> Response {
    let mut r = Response::new(Body::from(text));
    *r.status_mut() = status;
    cors(r.headers_mut());
    if let Some(n) = note.and_then(|n| HeaderValue::from_str(&n.chars().take(200).collect::<String>()).ok()) {
        r.headers_mut().insert(HeaderName::from_static("x-relay-error"), n);
    }
    r
}

async fn relay(
    State(ctx): State<Arc<Ctx>>,
    method: Method,
    Query(q): Query<HashMap<String, String>>,
    incoming: HeaderMap,
    body: Bytes,
) -> Response {
    if method == Method::OPTIONS {
        return plain(StatusCode::NO_CONTENT, "", None);
    }
    if q.get("s").map(String::as_str) != Some(ctx.secret.as_str()) {
        return plain(StatusCode::FORBIDDEN, "forbidden", None);
    }
    let Some(url) = q.get("u").filter(|u| u.starts_with("http://") || u.starts_with("https://")) else {
        return plain(StatusCode::BAD_REQUEST, "bad url", None);
    };
    let extra: HashMap<String, String> = q
        .get("h")
        .and_then(|h| URL_SAFE_NO_PAD.decode(h.trim_end_matches('=')).ok())
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();

    let up_method = match method {
        Method::POST => reqwest::Method::POST,
        Method::HEAD => reqwest::Method::HEAD,
        _ => reqwest::Method::GET,
    };
    let mut req = ctx.client.request(up_method, url.as_str());
    let mut has_range = false;
    for (k, v) in &extra {
        if k.eq_ignore_ascii_case("range") {
            has_range = true;
        }
        req = req.header(k.as_str(), v.as_str());
    }
    if !has_range {
        if let Some(r) = incoming.get(header::RANGE).and_then(|v| v.to_str().ok()) {
            req = req.header("Range", r);
        }
    }
    if !extra.keys().any(|k| k.eq_ignore_ascii_case("accept-encoding")) {
        if let Some(e) = incoming.get(header::ACCEPT_ENCODING).and_then(|v| v.to_str().ok()) {
            req = req.header("Accept-Encoding", e);
        }
    }
    if method == Method::POST && !body.is_empty() {
        req = req.body(body);
    }
    let pieces = method == Method::GET && q.get("p").map(String::as_str) == Some("1") && !has_range && incoming.get(header::RANGE).is_none();
    if pieces {
        return relay_pieces(&ctx, url, extra).await;
    }

    let send = tokio::time::timeout(Duration::from_secs(20), req.send()).await;
    let up = match send {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => return plain(StatusCode::BAD_GATEWAY, "relay: upstream failed", Some(e.to_string())),
        Err(_) => return plain(StatusCode::GATEWAY_TIMEOUT, "relay: no answer", Some("timeout".into())),
    };
    respond(up, method == Method::HEAD)
}

/// The answer as the page gets it: status, the headers worth passing, the
/// final URL, and the body streamed through untouched.
fn respond(up: reqwest::Response, head_only: bool) -> Response {
    let status = StatusCode::from_u16(up.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    let mut out = HeaderMap::new();
    cors(&mut out);
    for name in PASS {
        if let Some(v) = up.headers().get(name).and_then(|v| HeaderValue::from_bytes(v.as_bytes()).ok()) {
            out.insert(HeaderName::from_static(name), v);
        }
    }
    let cookies: Vec<String> = up
        .headers()
        .get_all(reqwest::header::SET_COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok().map(str::to_owned))
        .collect();
    if !cookies.is_empty() {
        if let Ok(json) = serde_json::to_vec(&cookies) {
            if let Ok(v) = HeaderValue::from_str(&URL_SAFE_NO_PAD.encode(json)) {
                out.insert(HeaderName::from_static("x-set-cookie"), v);
            }
        }
    }
    if let Ok(v) = HeaderValue::from_str(up.url().as_str()) {
        out.insert(HeaderName::from_static("x-final-url"), v);
    }
    let body = if head_only {
        Body::empty()
    } else {
        Body::from_stream(up.bytes_stream())
    };
    let mut r = Response::new(body);
    *r.status_mut() = status;
    *r.headers_mut() = out;
    r
}

// ---- one file in parallel pieces (`p=1`) ----
//
// Some live servers give each connection a trickle (cdn.hdm1.net, 2026-11-18:
// 0.4-0.9 Mbit/s for one connection, a stream that needs 3). Asking for the
// file in 128 KB ranges over 16 connections at once fetched the same chunk
// 1.5-4x faster. Same recipe as the TV app's ChunkPieces: every piece starts
// as soon as the first answer gives the size, a piece cut short resumes where
// it stopped, and the pieces reach the page in order as they complete.

const PIECE: u64 = 128 * 1024;
/// The first piece is small so bytes reach the player within a second or
/// two: pieces arrive in order, and a player that hears nothing for 8 s
/// gives up on the chunk (measured: every 128 KB first piece on a trickling
/// server ran past that).
const FIRST_PIECE: u64 = 16 * 1024;
const PIECE_CONNS: usize = 16;
/// A piece connection that sends nothing for this long is replaced.
const PIECE_IDLE: Duration = Duration::from_secs(8);
const PIECE_TRIES: u32 = 5;

fn piece_request(
    client: &reqwest::Client,
    url: &str,
    headers: &HashMap<String, String>,
    a: u64,
    b: u64,
) -> reqwest::RequestBuilder {
    let mut req = client.get(url);
    for (k, v) in headers {
        if !k.eq_ignore_ascii_case("accept-encoding") && !k.eq_ignore_ascii_case("range") {
            req = req.header(k.as_str(), v.as_str());
        }
    }
    // Ranges of a compressed answer can't be put back together.
    req.header("Accept-Encoding", "identity").header("Range", format!("bytes={a}-{b}"))
}

fn content_total(r: &reqwest::Response) -> Option<u64> {
    let v = r.headers().get(reqwest::header::CONTENT_RANGE)?.to_str().ok()?;
    v.rsplit('/').next()?.trim().parse().ok()
}

/// Bytes [a, b] of [url], resuming on a fresh connection when one stalls or
/// is cut. [first]: an answer already open for this range.
async fn fetch_piece(
    client: reqwest::Client,
    url: String,
    headers: Arc<HashMap<String, String>>,
    a: u64,
    b: u64,
    mut first: Option<reqwest::Response>,
) -> Result<Bytes, std::io::Error> {
    use futures_util::StreamExt;
    let want = (b - a + 1) as usize;
    let mut buf: Vec<u8> = Vec::with_capacity(want);
    let mut tries = 0;
    loop {
        if buf.len() >= want {
            buf.truncate(want);
            return Ok(Bytes::from(buf));
        }
        let r = match first.take() {
            Some(r) => r,
            None => {
                tries += 1;
                if tries > PIECE_TRIES {
                    return Err(std::io::Error::new(std::io::ErrorKind::TimedOut, "piece kept stalling"));
                }
                let start = a + buf.len() as u64;
                match tokio::time::timeout(Duration::from_secs(10), piece_request(&client, &url, &headers, start, b).send()).await {
                    Ok(Ok(r)) if r.status() == reqwest::StatusCode::PARTIAL_CONTENT => r,
                    Ok(Ok(r)) => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            format!("piece answered HTTP {}", r.status().as_u16()),
                        ))
                    }
                    _ => continue,
                }
            }
        };
        let mut s = r.bytes_stream();
        loop {
            match tokio::time::timeout(PIECE_IDLE, s.next()).await {
                Ok(Some(Ok(chunk))) => {
                    let room = want - buf.len();
                    buf.extend_from_slice(&chunk[..chunk.len().min(room)]);
                    if buf.len() >= want {
                        break;
                    }
                }
                // Ended early, failed or went quiet: resume from here.
                _ => break,
            }
        }
    }
}

async fn relay_pieces(ctx: &Ctx, url: &str, headers: HashMap<String, String>) -> Response {
    use futures_util::{future::BoxFuture, stream, FutureExt, StreamExt};
    let first = match tokio::time::timeout(
        Duration::from_secs(20),
        piece_request(&ctx.client, url, &headers, 0, FIRST_PIECE - 1).send(),
    )
    .await
    {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => return plain(StatusCode::BAD_GATEWAY, "relay: upstream failed", Some(e.to_string())),
        Err(_) => return plain(StatusCode::GATEWAY_TIMEOUT, "relay: no answer", Some("timeout".into())),
    };
    // No ranges here (or an error): the answer as it is.
    let total = match (first.status() == reqwest::StatusCode::PARTIAL_CONTENT, content_total(&first)) {
        (true, Some(t)) if t > 0 => t,
        _ => return respond(first, false),
    };

    let mut out = HeaderMap::new();
    cors(&mut out);
    if let Some(v) = first.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v| HeaderValue::from_bytes(v.as_bytes()).ok()) {
        out.insert(header::CONTENT_TYPE, v);
    }
    out.insert(header::CONTENT_LENGTH, HeaderValue::from(total));
    if let Ok(v) = HeaderValue::from_str(first.url().as_str()) {
        out.insert(HeaderName::from_static("x-final-url"), v);
    }
    out.insert(HeaderName::from_static("x-relay-pieces"), HeaderValue::from(1 + total.saturating_sub(FIRST_PIECE).div_ceil(PIECE)));

    let headers = Arc::new(headers);
    let mut jobs: Vec<BoxFuture<'static, Result<Bytes, std::io::Error>>> = Vec::new();
    let end0 = (FIRST_PIECE - 1).min(total - 1);
    jobs.push(fetch_piece(ctx.client.clone(), url.to_string(), headers.clone(), 0, end0, Some(first)).boxed());
    let mut a = FIRST_PIECE;
    while a < total {
        let b = (a + PIECE - 1).min(total - 1);
        jobs.push(fetch_piece(ctx.client.clone(), url.to_string(), headers.clone(), a, b, None).boxed());
        a += PIECE;
    }
    // In order, up to PIECE_CONNS running at once.
    let body = stream::iter(jobs).buffered(PIECE_CONNS);
    let mut r = Response::new(Body::from_stream(body));
    *r.status_mut() = StatusCode::OK;
    *r.headers_mut() = out;
    r
}

/// `GET /capture?s=<secret>&u=<page>&t=<ms>`: the hidden player window (see
/// capture.rs). Answers `{"ok":true,"url",...}` or `{"ok":false,"error"}`.
async fn capture_route(
    State(ctx): State<Arc<Ctx>>,
    method: Method,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    if method == Method::OPTIONS {
        return plain(StatusCode::NO_CONTENT, "", None);
    }
    if q.get("s").map(String::as_str) != Some(ctx.secret.as_str()) {
        return plain(StatusCode::FORBIDDEN, "forbidden", None);
    }
    let Some(url) = q.get("u").cloned() else {
        return plain(StatusCode::BAD_REQUEST, "bad url", None);
    };
    let timeout = q.get("t").and_then(|t| t.parse().ok());
    let body = match crate::capture::capture(ctx.app.clone(), url, timeout).await {
        Ok(c) => serde_json::json!({ "ok": true, "url": c.url, "origin": c.origin, "page": c.page, "ua": c.ua }),
        Err(e) => serde_json::json!({ "ok": false, "error": e }),
    };
    let mut r = Response::new(Body::from(body.to_string()));
    cors(r.headers_mut());
    r.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json"));
    r
}
