/* Vijana BaruBaru — phone-remote SPA */

const $  = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);
const TOKEN_KEY = "vbtv.token";
const THEME_KEY = "vbtv.theme";

let token = localStorage.getItem(TOKEN_KEY) || "";
let me = null;            // { token, role, label }
let prefs = { networks: [], genres: [], denyLanguages: [] };
let lastDetails = null;
let searchTimer = null;
let recoverTimer = null;  // re-auth poll when locked out
let active = "browse";    // active tab — Browse is the discovery default
let browseLoaded = false;
// Live TV state — populated on first open and refreshed when the channels
// cache on the TV finishes loading.
let liveLoaded = false;
let liveChannelsAll = [];
let liveSelectedGroup = "";
let liveQuery = "";
let liveQueryTimer = null;
let livePollTimer = null;

/* ---------- icon helper ---------- */
const ic = (id, cls = "") => `<svg class="icon ${cls}"><use href="#i-${id}"/></svg>`;

/* ---------- theme ---------- */
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  const use = document.getElementById("themeIcon");
  if (use) use.setAttribute("href", t === "light" ? "#i-moon" : "#i-sun");
}
function initTheme() {
  let t = localStorage.getItem(THEME_KEY);
  if (!t) t = matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  applyTheme(t);
}
initTheme();

/* ---------- fetch wrapper ---------- */
async function api(path, opts = {}) {
  const headers = Object.assign(
    { "Authorization": token ? "Bearer " + token : "" },
    opts.headers || {},
  );
  let res;
  try {
    res = await fetch(path, Object.assign({}, opts, { headers }));
  } catch (e) {
    showBanner("Network unreachable — retrying…", "error");
    throw e;
  }
  hideBanner();
  if (res.status === 403) {
    // Token rejected — start a quiet recovery poll instead of nuking immediately.
    startRecoveryPoll();
    throw new Error("forbidden");
  }
  return res;
}
const get  = (p)        => api(p).then(r => r.json());
const post = (p, body) => api(p, { method: "POST", body: body || null })
  .then(r => r.json());

/* ---------- toast ---------- */
let toastTimer;
function toast(msg) {
  const t = $("#toast"); t.textContent = msg; t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 1800);
}

/* ---------- banner (offline / re-auth) ---------- */
function showBanner(text, kind) {
  const b = $("#banner");
  b.textContent = ""; b.appendChild(svgInline("i-bulb"));
  const span = document.createElement("span"); span.textContent = " " + text;
  b.appendChild(span);
  b.className = "banner" + (kind === "error" ? " error" : "");
}
function svgInline(id) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "icon sm");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#" + id); svg.appendChild(use); return svg;
}
function hideBanner() { $("#banner").classList.add("hidden"); }

/* ---------- pair gate ---------- */
function showGate(opts = {}) {
  $("#gate").classList.remove("hidden");
  $("#app").classList.add("hidden");
  const err = $("#gateError"), wait = $("#gateWait");
  err.classList.toggle("hidden", !opts.error);
  if (opts.error) err.textContent = opts.error;
  wait.classList.toggle("hidden", !opts.waiting);
  if (opts.waiting) $("#gateWaitText").textContent = opts.waiting;
  $("#gateTitle").textContent = opts.title || "Pair this phone";
  $("#gateBody").textContent  = opts.body  ||
    "Enter the 6-character code shown on the TV — Settings → Remotes.";
}
function hideGate() {
  $("#gate").classList.add("hidden");
  $("#app").classList.remove("hidden");
}

async function pair(code) {
  const p = new URLSearchParams(); if (code) p.set("code", code);
  const res = await fetch("/api/pair?" + p.toString(), { method: "POST" });
  const j = await res.json();
  if (!j.token) throw new Error(j.error || "Pair failed");
  token = j.token; me = j;
  localStorage.setItem(TOKEN_KEY, token);
  return j;
}

$("#codeBtn").onclick = async () => {
  const code = $("#codeInput").value.trim().toUpperCase();
  if (code.length < 4) {
    showGate({ error: "Enter the code from the TV." }); return;
  }
  try {
    await pair(code);
    await afterPair();
  } catch (e) {
    showGate({ error: "Could not pair — check the code and try again." });
  }
};

$("#themeBtn").onclick = () => {
  const next = (document.documentElement.dataset.theme === "light") ? "dark" : "light";
  localStorage.setItem(THEME_KEY, next); applyTheme(next);
};

async function afterPair() {
  hideGate();
  await loadMe();
  if (!me) return;
  if (me.role === "PENDING") {
    showGate({
      title: "Waiting for approval",
      body: "Ask the TV owner to approve this phone in Settings → Remotes.",
      waiting: "Listening for approval…",
    });
    startRecoveryPoll();
    return;
  }
  try { prefs = await get("/api/me/prefs"); } catch (e) {}
  await loadHistory();
  selectTab(active === "details" ? "browse" : active);
  toast("Connected as " + me.label);
  // Non-blocking: ask the TV whether a newer release is on GitHub. Banner
  // hides itself if the user already dismissed this exact version.
  checkUpdate();
  // Network status banner — also handles the "phone can't reach TV" case
  // (we treat that the same as "TV is offline" from a UX perspective).
  startNetworkPoll();
}

async function loadMe() {
  try {
    const r = await api("/api/me");
    if (r.status !== 200) { me = null; return; }
    me = await r.json();
  } catch (e) { me = null; return; }
  $("#meLabel").textContent = me.label;
  const role = $("#meRole");
  role.textContent = me.role; role.className = "role " + me.role;
  // Devices view: managing other phones' permissions is genuinely
  // superuser-only. Debug pane is read-only and the data is already
  // token-gated, so make it available to any paired device — otherwise
  // a single misconfigured pair (reinstall, lost SUPERUSER) hides the
  // most useful diagnostic surface.
  if (me.role === "SUPERUSER") {
    $("#devicesBtn").classList.remove("hidden"); loadDevices();
  } else {
    $("#devicesBtn").classList.add("hidden");
  }
  if (me.role === "SUPERUSER" || me.role === "USER") {
    $("#debugBtn").classList.remove("hidden");
  } else {
    $("#debugBtn").classList.add("hidden");
  }
}

/* ---------- recovery: 403 or PENDING → poll until usable ---------- */
function startRecoveryPoll() {
  if (recoverTimer) return;
  recoverTimer = setInterval(async () => {
    if (!token) { stopRecoveryPoll(); return; }
    try {
      const r = await fetch("/api/me",
        { headers: { "Authorization": "Bearer " + token } });
      if (r.status === 200) {
        const j = await r.json();
        me = j;
        if (j.role === "PENDING") {
          showGate({
            title: "Waiting for approval",
            body: "Ask the TV owner to approve this phone in Settings → Remotes.",
            waiting: "Listening for approval…",
          });
          return;
        }
        stopRecoveryPoll();
        hideGate(); await afterRecover();
      } else if (r.status === 403) {
        // Token removed or device blocked — clear and ask for a new pair.
        token = ""; localStorage.removeItem(TOKEN_KEY);
        stopRecoveryPoll();
        showGate({ error: "This phone was removed — pair again." });
      }
    } catch (e) { /* keep polling */ }
  }, 3000);
}
function stopRecoveryPoll() {
  if (recoverTimer) { clearInterval(recoverTimer); recoverTimer = null; }
}
async function afterRecover() {
  await loadMe(); await loadHistory();
  selectTab(active === "details" ? "search" : active);
  toast("Reconnected");
}

/* ---------- bootstrap ---------- */
async function boot() {
  const params = new URLSearchParams(location.search);
  const urlCode = params.get("pair");
  if (urlCode && !token) {
    try { await pair(urlCode); history.replaceState(null, "", "/"); }
    catch (e) { showGate({ error: "Could not pair — open the QR on the TV again." }); return; }
  }
  if (!token) { showGate(); return; }
  try {
    const r = await fetch("/api/me",
      { headers: { "Authorization": "Bearer " + token } });
    if (r.status !== 200) {
      // Maybe the device was removed; offer to re-pair, but keep polling.
      showGate({ error: "Pairing expired — pair again or wait for re-approval." });
      startRecoveryPoll(); return;
    }
    me = await r.json();
  } catch (e) { showGate(); return; }
  await afterPair();
}

/* ---------- tabs ---------- */
function selectTab(name) {
  active = name;
  $$(".pane").forEach(p => p.classList.remove("active"));
  $$(".tab").forEach(b => b.classList.remove("active"));
  const pane = $("#pane-" + name); if (pane) pane.classList.add("active");
  const btn = document.querySelector(`.tab[data-pane="${name}"]`);
  if (btn) btn.classList.add("active");
  if (name === "np") refresh();
  if (name === "dl") loadDownloads();
  if (name === "devices") loadDevices();
  if (name === "debug") openDebug();
  else closeDebug();
  if (name === "browse" && !browseLoaded) loadBrowse();
  if (name === "live" && !liveLoaded) loadLive();
  if (name === "prefs") loadPrefs();
}
$$(".tab").forEach(b => b.onclick = () => selectTab(b.dataset.pane));

// Topbar Devices + Debug shortcuts (superuser only — visibility from fetchMe()).
const devicesBtn = $("#devicesBtn");
if (devicesBtn) devicesBtn.onclick = () => selectTab("devices");
const debugBtn = $("#debugBtn");
if (debugBtn) debugBtn.onclick = () => selectTab("debug");

/* ---------- Update banner ---------- */
async function checkUpdate() {
  let r;
  try { r = await get("/api/update"); } catch { return; }
  if (!r || !r.tag) return;   // device says "available:false" → no tag field
  // The user might have dismissed THIS specific version already; honour that.
  if (localStorage.getItem("vbtv.dismissedUpdate") === r.tag) return;
  $("#ubVersion").textContent = "Update available — " + (r.name || ("v" + r.tag));
  const body = String(r.notes || "")
    .replace(/[#*`]+/g, "").split("\n").map(l => l.trim()).filter(Boolean).slice(0, 2).join(" · ");
  $("#ubBody").textContent = body || "Download the latest APK.";
  $("#ubDownload").href = r.apkUrl || r.htmlUrl || "#";
  $("#updateBanner").classList.remove("hidden");
  $("#ubDismiss").onclick = () => {
    localStorage.setItem("vbtv.dismissedUpdate", r.tag);
    $("#updateBanner").classList.add("hidden");
  };
}

/* ---------- Debug pane ---------- */
let debugTimer = null;
function openDebug() {
  if (debugTimer) return;
  refreshDebug();
  debugTimer = setInterval(refreshDebug, 2500);
}
function closeDebug() {
  if (debugTimer) { clearInterval(debugTimer); debugTimer = null; }
}
async function refreshDebug() {
  let d;
  try { d = await get("/api/debug"); } catch { return; }
  if (!d) return;

  const session = d.session || {};
  const cur = d.current || {};
  const channels = Array.isArray(d.channels) ? d.channels : [];
  const events = Array.isArray(d.events) ? d.events : [];
  const today = d.today || {};
  const week = d.week || {};
  const providers = Array.isArray(d.providers) ? d.providers : [];

  // Summary stat grid (this session)
  $("#debugSummary").innerHTML = [
    statHtml("Uptime", fmtUptime(session.uptimeMs), null),
    statHtml("Plays started", session.playsStarted ?? 0, null),
    statHtml("Failures", session.playsFailed ?? 0,
      `${session.httpErrors ?? 0} HTTP errors`),
    statHtml("Rebuffers", session.rebuffers ?? 0,
      `${session.freezes ?? 0} freezes`),
  ].join("");

  // Today + 7-day rollups
  $("#debugRollups").innerHTML = [
    rollupHtml("Today", today),
    rollupHtml("Last 7 days", week),
  ].join("");

  // Providers
  $("#debugProviders").innerHTML = providers.length === 0
    ? `<div class="muted small">No provider activity yet.</div>`
    : providers.map(p => {
        const cls = p.status === "down" ? "err"
                  : p.status === "degraded" ? "warn" : "ok";
        const label = p.status[0].toUpperCase() + p.status.slice(1);
        const lastFail = p.lastFailure
          ? `<div class="muted small">${escapeHtml(p.lastFailure)}</div>` : "";
        return `
          <div class="ch-row">
            <div>
              <div class="name">${escapeHtml(p.name)}</div>
              ${lastFail}
            </div>
            <div class="badges">
              ${badge(cls, label)}
              ${badge("ok", `${p.ok} ok`)}
              ${p.err > 0 ? badge("warn", `${p.err} err`) : ""}
            </div>
          </div>`;
      }).join("");

  // Current stream
  const ratingClass = "rating-" + (cur.rating || "excellent");
  const ratingPct = ({excellent:100, good:75, fair:45, poor:20})[cur.rating || "excellent"];
  const droppedPct = ((cur.droppedRatio || 0) * 100).toFixed(1);
  const bitrateMbps = ((cur.bitrateBps || 0) / 1e6).toFixed(2);
  $("#debugStream").innerHTML = cur.kind === "none" || !cur.title
    ? `<div class="muted small">Nothing playing.</div>`
    : `
      <div class="row"><span>Title</span><span>${escapeHtml(cur.title)}</span></div>
      <div class="row"><span>Kind</span><span>${escapeHtml(cur.kind)}</span></div>
      <div class="row"><span>Resolution</span><span>${escapeHtml(cur.resolution || "—")}</span></div>
      <div class="row"><span>Bitrate</span><span>${bitrateMbps} Mbps</span></div>
      <div class="row"><span>Buffer</span><span>${((cur.bufferMs||0)/1000).toFixed(1)}s</span></div>
      <div class="row"><span>Dropped frames</span><span>${droppedPct}%</span></div>
      <div class="row"><span>Quality</span><span>${escapeHtml(cur.rating || "—")}</span></div>
      <div class="rating-bar"><div class="${ratingClass}" style="width:${ratingPct}%"></div></div>
    `;

  // Channels list — show troublesome ones first (server already sorted by
  // failures*10 + freezes*5 + rebuffers). Limit to 15 to keep the page short.
  $("#debugScope").textContent = channels.length
    ? `(${channels.length} channel${channels.length === 1 ? "" : "s"} touched)`
    : "";
  $("#debugChannels").innerHTML = channels.length === 0
    ? `<div class="muted small">No channels played yet.</div>`
    : channels.slice(0, 15).map(ch => {
        const badges = [];
        if (ch.failures > 0) badges.push(badge("err", `${ch.failures} fail`));
        if (ch.freezes > 0)  badges.push(badge("err", `${ch.freezes} froze`));
        if (ch.rebuffers > 0) badges.push(badge("warn", `${ch.rebuffers} reb`));
        badges.push(badge("ok", `${ch.plays} play${ch.plays === 1 ? "" : "s"}`));
        const lastFail = ch.lastFailure ? `<div class="muted small">${escapeHtml(ch.lastFailure)}</div>` : "";
        return `
          <div class="ch-row">
            <div>
              <div class="name">${escapeHtml(ch.name || ch.id)}</div>
              ${lastFail}
            </div>
            <div class="badges">${badges.join("")}</div>
          </div>`;
      }).join("");

  // Events
  $("#debugEvents").innerHTML = events.length === 0
    ? `<div class="muted small">No events logged yet.</div>`
    : events.slice(0, 25).map(e => `
        <div class="ev severity-${escapeHtml(e.severity || "info")}">
          <span class="ts">${fmtTs(e.atMs)}</span>
          <span class="body">${escapeHtml(e.message || "")}</span>
        </div>`).join("");
}

function statHtml(label, value, sub) {
  return `
    <div class="stat">
      <span class="label">${escapeHtml(label)}</span>
      <span class="value">${escapeHtml(String(value))}</span>
      ${sub ? `<span class="sub">${escapeHtml(sub)}</span>` : ""}
    </div>`;
}
function rollupHtml(label, bucket) {
  const b = bucket || {};
  return `
    <div class="roll">
      <span class="label">${escapeHtml(label)}</span>
      <div class="nums">
        <span>Plays</span>     <span>${b.plays    ?? 0}</span>
        <span>Failed</span>    <span>${b.failed   ?? 0}</span>
        <span>Freezes</span>   <span>${b.freezes  ?? 0}</span>
        <span>Rebuffers</span> <span>${b.rebuffers?? 0}</span>
        <span>HTTP errs</span> <span>${b.httpErrors ?? 0}</span>
      </div>
    </div>`;
}

// Clear button — POST /api/debug/clear, refresh once so the user sees the reset.
const debugClearBtn = $("#debugClearBtn");
if (debugClearBtn) {
  debugClearBtn.onclick = async () => {
    debugClearBtn.disabled = true;
    debugClearBtn.textContent = "Clearing…";
    try {
      await post("/api/debug/clear");
      await refreshDebug();
      showToast("Logs cleared");
    } catch (e) { showToast("Couldn't clear"); }
    debugClearBtn.disabled = false;
    debugClearBtn.textContent = "Clear";
  };
}

/* ---------- Network status banner ----------
 * Polls /api/network every 8s — light, doesn't need to be precise. State
 * comes from the TV's NetworkMonitor (Online / Checking / OfflineLong).
 * Banner is hidden when "online", quiet when "checking", severe when
 * offline >3 min OR when we can't reach the TV at all. */
let netBannerTimer = null;
async function pollNetwork() {
  let r = null;
  let phoneCanReachTv = true;
  try { r = await get("/api/network"); }
  catch { phoneCanReachTv = false; }
  const banner = $("#netBanner");
  const text = $("#netBannerText");
  if (!phoneCanReachTv) {
    banner.classList.remove("hidden");
    banner.classList.add("severe");
    text.textContent = "Phone can't reach the TV. Are you on the same Wi-Fi?";
    return;
  }
  const state = r && r.state;
  if (!state || state === "online") {
    banner.classList.add("hidden");
    banner.classList.remove("severe");
    return;
  }
  banner.classList.remove("hidden");
  if (state === "checking") {
    banner.classList.remove("severe");
    text.textContent = "Checking the TV's connection…";
  } else { // offlinelong
    banner.classList.add("severe");
    const mins = Math.floor((r.sinceMs || 0) / 60000);
    text.textContent = mins > 60
      ? "TV's been offline over an hour. Try moving closer to the router."
      : "TV is offline. We'll keep watching for it.";
  }
}
function startNetworkPoll() {
  if (netBannerTimer) return;
  pollNetwork();
  netBannerTimer = setInterval(pollNetwork, 8000);
}
function badge(severity, text) {
  return `<span class="badge ${severity}">${escapeHtml(text)}</span>`;
}
function fmtUptime(ms) {
  if (!ms) return "0s";
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${r}s`;
  return `${r}s`;
}
function fmtTs(elapsedMs) {
  const s = Math.floor(elapsedMs / 1000);
  const m = Math.floor(s / 60), r = s % 60;
  return `${m}:${String(r).padStart(2, "0")}`;
}

/* ---------- search ---------- */
$("#q").addEventListener("input", (e) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => doSearch(e.target.value), 320);
});
async function doSearch(q) {
  const grid = $("#results");
  if (q.trim().length < 2) {
    grid.innerHTML = ""; $("#searchEmpty").classList.remove("hidden"); return;
  }
  $("#searchEmpty").classList.add("hidden");
  grid.innerHTML = skeletonGrid(6);
  let items = [];
  try { items = await get("/api/search?q=" + encodeURIComponent(q)); }
  catch (e) { grid.innerHTML = `<div class="muted small">Search failed.</div>`; return; }
  if (!items.length) {
    grid.innerHTML = `<div class="muted small">No matches.</div>`; return;
  }
  grid.innerHTML = "";
  items.forEach(it => grid.appendChild(searchCard(it)));
}
function skeletonGrid(n) {
  let out = ""; for (let i = 0; i < n; i++) {
    out += `<div class="card-item">
      <div class="poster"></div>
      <div class="meta"><div class="t" style="background:var(--surface-2);height:1.2em;border-radius:6px"></div></div>
    </div>`;
  } return out;
}
function searchCard(it) {
  const el = document.createElement("div");
  el.className = "card-item";
  const badge = it.isSeries
    ? `<span class="badge tv">TV</span>`
    : `<span class="badge movie">Movie</span>`;
  const rating = it.rating
    ? `<span class="rating">${ic("star","sm")}${it.rating.toFixed(1)}</span>` : "";
  el.innerHTML = `
    <img class="poster" loading="lazy" src="${it.cover}" onerror="this.style.opacity=.3" />
    <div class="meta">
      <div class="t">${escapeHtml(it.title)}</div>
      <div class="s">${badge}${it.year ? `<span>${it.year}</span>` : ""}${rating}</div>
    </div>`;
  el.onclick = () => openDetails(it);
  return el;
}
function escapeHtml(s) {
  return (s || "").replace(/[&<>"]/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;",
  }[c]));
}

/* ---------- details ---------- */
async function openDetails(it) {
  lastDetails = { item: it, seasons: [] };
  selectTab("details");
  $("#dCover").src = it.cover || "";
  $("#dTitle").textContent = it.title;
  const subParts = [];
  subParts.push(it.isSeries ? "TV" : "Movie");
  if (it.year)   subParts.push(it.year);
  if (it.rating) subParts.push("★ " + it.rating.toFixed(1));
  $("#dSub").textContent = subParts.join(" · ");
  $("#dDesc").textContent = "Loading…";
  $("#dEpisodes").classList.toggle("hidden", !it.isSeries);

  // TMDB-browsed picks: no aoneroom details. Default S1E1 for series and
  // skip the description fetch (we'd need a TMDB detail call to get one).
  if (it.subjectId && it.subjectId.startsWith("tmdb:")) {
    $("#dDesc").textContent = it.overview || "";
    if (it.isSeries) {
      const sel = $("#dSeason"); sel.innerHTML = "";
      [1,2,3,4,5,6,7,8].forEach(s => {
        const o = document.createElement("option");
        o.value = s; o.textContent = "Season " + s;
        sel.appendChild(o);
      });
      $("#dEpisode").value = 1;
    }
    return;
  }

  try {
    const d = await get("/api/details?subjectId=" + encodeURIComponent(it.subjectId));
    $("#dDesc").textContent = d.description || "";
    if (it.isSeries && d.seasons && d.seasons.length) {
      lastDetails.seasons = d.seasons;
      const sel = $("#dSeason"); sel.innerHTML = "";
      d.seasons.forEach(s => {
        const o = document.createElement("option");
        o.value = s.season; o.textContent = "Season " + s.season;
        sel.appendChild(o);
      });
      $("#dEpisode").value = 1;
    }
  } catch (e) { $("#dDesc").textContent = ""; }
}
$("#detailsBack").onclick = () => selectTab("search");

$("#dPlay").onclick = async () => {
  if (!lastDetails) return;
  const it = lastDetails.item;
  const p = new URLSearchParams({
    subjectId: it.subjectId, title: it.title, cover: it.cover || "", type: it.type,
  });
  if (it.year)     p.set("year", it.year);
  if (it.isSeries) {
    p.set("se", $("#dSeason").value || "1");
    p.set("ep", $("#dEpisode").value || "1");
  }
  await post("/api/play?" + p.toString());
  toast("Playing on TV…"); selectTab("np");
};
$("#dDl").onclick = async () => {
  if (!lastDetails) return;
  const it = lastDetails.item;
  const p = new URLSearchParams({
    subjectId: it.subjectId, title: it.title, cover: it.cover || "", type: it.type,
  });
  if (it.year)     p.set("year", it.year);
  if (it.isSeries) {
    p.set("se", $("#dSeason").value || "1");
    p.set("ep", $("#dEpisode").value || "1");
  }
  await post("/api/downloads/start?" + p.toString());
  toast("Queued for download"); selectTab("dl");
};

/* ---------- now playing ---------- */
const fmt = (ms) => { const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60); return m + ":" + String(s % 60).padStart(2, "0"); };

/** True while the user is actively dragging the volume slider — we want to
 *  pause the refresh→update→snap-back loop until they let go. */
let volSliding = false;

async function refresh() {
  try {
    const s = await get("/api/state");
    $("#npTitle").textContent = s.title || "Nothing playing";
    $("#npPos").textContent = fmt(s.position || 0);
    $("#npDur").textContent = fmt(s.duration || 0);
    $("#npBar").style.width = (s.duration ? (s.position / s.duration * 100) : 0) + "%";
    // Skip the play/pause icon swap if it's already correct — every textContent
    // / innerHTML write is a layout invalidation, which is what made tapping
    // feel like the page "jumped" each second when the poll fired.
    const wantPlay = s.playing ? "pause" : "play";
    if ($("#pp").dataset.icon !== wantPlay) {
      $("#pp").innerHTML = ic(wantPlay, "lg");
      $("#pp").dataset.icon = wantPlay;
    }
    const volStr = (s.volume ?? "—") + (s.volume != null ? "%" : "");
    if ($("#volPct").textContent !== volStr) $("#volPct").textContent = volStr;
    if (!volSliding && typeof s.volume === "number" &&
        Number($("#volSlider").value) !== s.volume) {
      $("#volSlider").value = s.volume;
    }
    syncTracks(s);
  } catch (e) { /* ignore */ }
}

/** Render the quality + audio pickers when there's an active stream. */
function syncTracks(s) {
  const qs = s.qualities || [], ds = s.dubs || [];
  const card = $("#trackCard");
  // Use the `hidden` attribute (boolean) rather than toggling style.display.
  // Together with the layout-stability rules in CSS, this avoids the brief
  // reflow the user saw every time playback state ticked.
  const show = qs.length > 0 || ds.length > 0;
  if (card.hidden === !show) { /* no-op when state is unchanged */ }
  else card.hidden = !show;
  if (!show) return;
  fillSelect($("#qualitySel"), qs, s.quality, async (v) => {
    await post("/api/quality?label=" + encodeURIComponent(v));
    toast("Quality: " + v);
  });
  fillSelect($("#dubSel"), ds, s.dub, async (v) => {
    await post("/api/dub?name=" + encodeURIComponent(v));
    toast("Audio: " + v);
  });
}

function fillSelect(sel, options, current, onPick) {
  // Skip a rebuild if the option set hasn't changed (avoids dropping focus).
  const sig = options.join("|") + "::" + (current || "");
  if (sel.dataset.sig === sig) return;
  sel.dataset.sig = sig;
  sel.innerHTML = "";
  options.forEach(o => {
    const opt = document.createElement("option");
    opt.value = o; opt.textContent = o;
    if (o === current) opt.selected = true;
    sel.appendChild(opt);
  });
  sel.onchange = () => onPick(sel.value);
}
$("#pp").onclick     = async () => { await post("/api/playpause"); refresh(); };
$("#back10").onclick = async () => { await post("/api/seekby?ms=-10000"); refresh(); };
$("#fwd10").onclick  = async () => { await post("/api/seekby?ms=10000");  refresh(); };
$("#volUp").onclick  = async () => { await post("/api/volume?up=1");      refresh(); };
$("#volDown").onclick= async () => { await post("/api/volume?down=1");    refresh(); };

/* ---------- volume slider ---------- */
let volSendTimer = null;
$("#volSlider").addEventListener("pointerdown", () => { volSliding = true; });
$("#volSlider").addEventListener("pointerup",   () => { volSliding = false; });
$("#volSlider").addEventListener("pointercancel",() => { volSliding = false; });
$("#volSlider").addEventListener("input", (e) => {
  const v = Number(e.target.value);
  $("#volPct").textContent = v + "%";
  clearTimeout(volSendTimer);
  // Debounce the network call — sliding fires `input` many times per second.
  volSendTimer = setTimeout(() => {
    post("/api/volume?set=" + v);
  }, 120);
});

/* ---------- history ---------- */
async function loadHistory() {
  let items = []; try { items = await get("/api/history"); } catch (e) {}
  const el = $("#history"); el.innerHTML = "";
  if (!items.length) {
    el.innerHTML = `<div class="muted small">Nothing watched yet.</div>`; return;
  }
  items.forEach(it => {
    const label = it.season > 0 ? `S${it.season}E${it.episode}` : "Resume";
    const pct = Math.round((it.progress || 0) * 100);
    const row = document.createElement("div"); row.className = "row-item";
    row.innerHTML = `
      <img loading="lazy" src="${it.cover}" onerror="this.style.opacity=.3" />
      <div class="body">
        <div class="t">${escapeHtml(it.title)}</div>
        <div class="s">${label} · ${pct}%</div>
        <div class="minibar"><div style="width:${pct}%"></div></div>
      </div>
      <div class="x" title="Remove">${ic("close","sm")}</div>`;
    row.querySelector(".body").onclick = async () => {
      await post("/api/play?" + new URLSearchParams({
        subjectId: it.subjectId, title: it.title, cover: it.cover, type: it.type,
        se: it.season || "", ep: it.episode || "",
      }));
      toast("Playing on TV…"); selectTab("np");
    };
    row.querySelector(".x").onclick = async (e) => {
      e.stopPropagation();
      await post("/api/history/delete?key=" + encodeURIComponent(it.key));
      loadHistory();
    };
    el.appendChild(row);
  });
}

/* ---------- downloads ---------- */
async function loadDownloads() {
  let items = []; try { items = await get("/api/downloads"); } catch (e) {}
  const el = $("#downloads"); el.innerHTML = "";
  $("#dlEmpty").classList.toggle("hidden", items.length > 0);
  items.forEach(it => {
    const label = it.season > 0 ? `S${it.season}E${it.episode}` : "Movie";
    const ready = it.status === "COMPLETE";
    const status = ready ? "Ready"
      : it.status === "DOWNLOADING" ? `${it.percent}% · downloading`
      : it.status === "FAILED" ? "Failed" : "Queued";
    const row = document.createElement("div"); row.className = "row-item";
    row.innerHTML = `
      <img loading="lazy" src="${it.cover}" onerror="this.style.opacity=.3" />
      <div class="body">
        <div class="t">${escapeHtml(it.title)}</div>
        <div class="s">
          ${ready ? ic("check","sm") : ""}
          <span>${label} · ${status}</span>
        </div>
        <div class="minibar"><div style="width:${it.percent}%"></div></div>
      </div>
      <div class="x" title="Remove">${ic("trash","sm")}</div>`;
    row.querySelector(".x").onclick = async () => {
      await post("/api/downloads/delete?key=" + encodeURIComponent(it.key));
      loadDownloads();
    };
    el.appendChild(row);
  });
}

/* ---------- devices (superuser) ---------- */
async function loadDevices() {
  if (!me || me.role !== "SUPERUSER") return;
  let items = []; try { items = await get("/api/devices"); } catch (e) {}
  const el = $("#devices"); el.innerHTML = "";
  try {
    const code = await get("/api/pair_code"); $("#pairCode").textContent = code.code;
  } catch (e) {}
  items.forEach(d => {
    const card = document.createElement("div"); card.className = "card dev-card";
    card.innerHTML = `
      <div class="dev-row">
        <div>
          <div class="name">${escapeHtml(d.label)} ${d.isMe ? '<span class="badge">this phone</span>' : ''}</div>
          <div class="ip">${d.ip} · last seen ${ago(d.lastSeen)}</div>
        </div>
        <div class="actions">
          <select ${d.isMe ? "disabled" : ""}>
            <option value="SUPERUSER" ${d.role === "SUPERUSER" ? "selected" : ""}>Superuser</option>
            <option value="USER"      ${d.role === "USER" ? "selected" : ""}>User</option>
            <option value="PENDING"   ${d.role === "PENDING" ? "selected" : ""}>Pending</option>
            <option value="BLOCKED"   ${d.role === "BLOCKED" ? "selected" : ""}>Blocked</option>
          </select>
          <button class="icon-btn ghost" ${d.isMe ? "disabled" : ""} aria-label="Remove">
            ${ic("trash","sm")}
          </button>
        </div>
      </div>`;
    const sel = card.querySelector("select");
    const rm  = card.querySelector(".icon-btn");
    sel.onchange = async () => {
      await post("/api/devices/role?token=" + encodeURIComponent(d.token) + "&role=" + sel.value);
      toast("Updated"); loadDevices();
    };
    rm.onclick = async () => {
      await post("/api/devices/remove?token=" + encodeURIComponent(d.token));
      toast("Removed"); loadDevices();
    };
    el.appendChild(card);
  });
}
$("#regenCode").onclick = async () => {
  const j = await post("/api/pair_code/regen");
  $("#pairCode").textContent = j.code; toast("Pair code regenerated");
};
function ago(ms) {
  const s = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (s < 60) return s + "s ago";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

/* ---------- browse ---------- */
const BROWSE_ROWS = [
  { id: "trending",        title: "Trending today" },
  { id: "popular_movies",  title: "Popular movies" },
  { id: "popular_tv",      title: "Popular TV" },
  { id: "netflix", title: "Netflix",      net: "Netflix" },
  { id: "hbo",     title: "HBO",          net: "HBO" },
  { id: "disney",  title: "Disney+",      net: "Disney+" },
  { id: "prime",   title: "Prime Video",  net: "Prime" },
  { id: "apple",   title: "Apple TV+",    net: "Apple" },
  { id: "hulu",    title: "Hulu",         net: "Hulu" },
];

async function loadBrowse() {
  browseLoaded = true;
  const host = $("#browseRows"); host.innerHTML = "";

  // Float user's favourite networks to the top of the order.
  const favSet = new Set(prefs.networks || []);
  const ordered = [
    ...BROWSE_ROWS.filter(r => favSet.has(r.id)),
    ...BROWSE_ROWS.filter(r => !favSet.has(r.id)),
  ];

  ordered.forEach(r => {
    const row = document.createElement("div"); row.className = "row-h";
    row.innerHTML = `
      <h3>${escapeHtml(r.title)}
        ${r.net ? `<span class="net">${r.net}</span>` : ""}
      </h3>
      <div class="scroller" id="row-${r.id}">
        ${skeletonGrid(4)}
      </div>`;
    host.appendChild(row);
  });

  // Fetch each row independently — first ones paint fast, slow rows fill later.
  ordered.forEach(async (r) => {
    let items = [];
    try { items = await get("/api/browse?slice=" + encodeURIComponent(r.id)); }
    catch (e) { items = []; }
    const el = document.getElementById("row-" + r.id);
    if (!el) return;
    el.innerHTML = "";
    if (!items.length) {
      el.innerHTML = `<div class="muted small" style="padding:6px 0">Nothing here.</div>`;
      return;
    }
    items.forEach(it => el.appendChild(searchCard(it)));
  });
}

/* ---------- live TV ---------- */
async function loadLive() {
  liveLoaded = true;
  await refreshLive(/*showSkeleton*/ true);
  // If the TV didn't have channels cached yet, the first response will say
  // loaded=false. Poll every 1.5s until populated, then stop.
  if (livePollTimer) clearTimeout(livePollTimer);
  const poll = async () => {
    if (active !== "live") return;
    const resp = await fetchLiveChannels();
    if (resp && resp.loaded) {
      liveChannelsAll = resp.channels;
      renderLiveChannels();
      renderLiveGroups();
      return;
    }
    livePollTimer = setTimeout(poll, 1500);
  };
  if (!liveChannelsAll.length) livePollTimer = setTimeout(poll, 1500);
}

async function fetchLiveChannels() {
  const params = new URLSearchParams();
  if (liveSelectedGroup) params.set("group", liveSelectedGroup);
  if (liveQuery) params.set("q", liveQuery);
  try {
    return await get("/api/live/channels?" + params.toString());
  } catch (e) { return null; }
}

async function refreshLive(showSkeleton) {
  const grid = $("#liveChannels");
  const status = $("#liveStatus");
  if (showSkeleton) {
    grid.innerHTML = skeletonGrid(6);
    status.textContent = "Loading channels…";
  }
  const resp = await fetchLiveChannels();
  if (!resp) {
    status.textContent = "Could not reach the TV.";
    return;
  }
  liveChannelsAll = resp.channels;
  if (!resp.loaded && !liveChannelsAll.length) {
    status.textContent = "The TV is fetching the catalog — this takes a moment on first use.";
  } else {
    status.textContent = `${liveChannelsAll.length} live channel${liveChannelsAll.length === 1 ? "" : "s"}.`;
  }
  renderLiveChannels();
  renderLiveGroups();
}

function renderLiveChannels() {
  const grid = $("#liveChannels");
  if (!liveChannelsAll.length) {
    grid.innerHTML = `<div class="muted small" style="grid-column:1/-1">No channels match.</div>`;
    return;
  }
  grid.innerHTML = "";
  liveChannelsAll.forEach(ch => {
    const card = document.createElement("div");
    card.className = "livecard";
    const logoHtml = ch.logo
      ? `<img src="${escapeHtml(ch.logo)}" alt="" onerror="this.remove()"/>`
      : `<span class="ph-letters">${escapeHtml((ch.name||"").slice(0,3).toUpperCase())}</span>`;
    card.innerHTML = `
      <div class="logo-wrap">
        ${logoHtml}
        <span class="live-pill">LIVE</span>
      </div>
      <div class="name">${escapeHtml(ch.name)}</div>`;
    card.addEventListener("click", async () => {
      try {
        // Server reads params from the query string only (NanoHTTPD doesn't
        // auto-parse form bodies). Match the /api/play VOD pattern.
        await post("/api/live/play?id=" + encodeURIComponent(ch.id));
        showToast(`Playing ${ch.name}`);
      } catch (e) {
        showToast("Couldn't play");
      }
    });
    grid.appendChild(card);
  });
}

async function renderLiveGroups() {
  // Distinct groups from the currently-loaded channel set (cheap, no extra fetch).
  // For the "All" chip we keep it always present.
  let groups;
  try {
    groups = await get("/api/live/groups");
  } catch (e) {
    groups = Array.from(new Set(liveChannelsAll.map(c => c.group).filter(Boolean)));
  }
  const bar = $("#liveGroups");
  bar.innerHTML = "";
  const mk = (label, value, active) => {
    const b = document.createElement("button");
    b.className = "chip" + (active ? " active" : "");
    b.textContent = label;
    b.onclick = () => {
      liveSelectedGroup = value;
      refreshLive(true);
    };
    return b;
  };
  bar.appendChild(mk("All", "", liveSelectedGroup === ""));
  groups.forEach(g => bar.appendChild(mk(shortGroup(g), g, liveSelectedGroup === g)));
}

function shortGroup(g) {
  const i = g.indexOf(" (");
  return i > 0 ? g.slice(0, i) : g;
}

// Search box (debounced).
const liveSearchEl = document.getElementById("liveQ");
if (liveSearchEl) {
  liveSearchEl.addEventListener("input", (e) => {
    clearTimeout(liveQueryTimer);
    liveQueryTimer = setTimeout(() => {
      liveQuery = e.target.value || "";
      refreshLive(false);
    }, 300);
  });
}

/* ---------- Live sub-tab switching (Channels / Schedule) ---------- */
let liveScheduleLoaded = false;
function selectLiveSubtab(which) {
  // Toggle pill state + pane visibility. The Live tab now mirrors the TV's
  // Live structure (Channels grid vs Schedule by category).
  document.querySelectorAll("#pane-live .subtab").forEach(b => {
    b.classList.toggle("active", b.dataset.sub === which);
  });
  $("#liveChannelsPane").classList.toggle("hidden", which !== "channels");
  $("#liveSchedulePane").classList.toggle("hidden", which !== "schedule");
  if (which === "schedule" && !liveScheduleLoaded) loadLiveSchedule();
}
document.querySelectorAll("#pane-live .subtab").forEach(b => {
  b.onclick = () => selectLiveSubtab(b.dataset.sub);
});

/* ---------- Live Schedule ---------- */
let liveScheduleData = null;
let liveScheduleQuery = "";
let liveScheduleCategory = "all";
let liveScheduleQueryTimer = null;

// Meta-categories the user asked for, mapped from the raw category strings the
// catalog publishes (which look like "Soccer", "NBA", "Cricket Test Match", …).
// First match wins; "Other" catches anything not classified.
const SCHED_CATEGORIES = [
  { id: "all",       label: "All",      match: () => true },
  { id: "sports",    label: "Sports",   match: s => /\b(soccer|football|nfl|nba|mlb|nhl|cricket|tennis|rugby|hockey|boxing|mma|wrestl|racing|f1|formula|gp|golf|baseball|basketball|sports?|league|cup|liga|premier|champion|olympic)\b/i.test(s) },
  { id: "news",      label: "News",     match: s => /\b(news|breaking|world|politics?|press)\b/i.test(s) },
  { id: "movies",    label: "Movies",   match: s => /\b(movies?|film|cinema)\b/i.test(s) },
  { id: "shows",     label: "TV Shows", match: s => /\b(shows?|series|drama|talk|sitcom|reality|episodes?)\b/i.test(s) },
  { id: "other",     label: "Other",    match: () => true },
];

function classifyScheduleCategory(name) {
  const s = String(name || "");
  for (const cat of SCHED_CATEGORIES) {
    if (cat.id === "all" || cat.id === "other") continue;
    if (cat.match(s)) return cat.id;
  }
  return "other";
}

async function loadLiveSchedule() {
  liveScheduleLoaded = true;
  const status = $("#liveScheduleStatus");
  const list = $("#liveScheduleList");
  status.textContent = "Loading schedule…";
  list.innerHTML = "";
  let data;
  try {
    data = await get("/api/live/schedule");
  } catch (e) { status.textContent = "Could not reach the TV."; return; }
  if (!Array.isArray(data) || data.length === 0) {
    status.textContent = "No schedule available right now.";
    renderScheduleChips([]);
    return;
  }
  liveScheduleData = data;
  renderScheduleChips(data);
  renderLiveSchedule(data);
}

function renderScheduleChips(data) {
  // Always show the same meta-category set regardless of which categories the
  // current payload happens to populate — keeps the filter row stable so
  // tapping a chip doesn't shift other chips around (the original
  // "page moves on tap" complaint from much earlier).
  const bar = $("#schedChips");
  bar.innerHTML = "";
  SCHED_CATEGORIES.forEach(cat => {
    const chip = document.createElement("div");
    chip.className = "chip" + (cat.id === liveScheduleCategory ? " active" : "");
    chip.textContent = cat.label;
    chip.onclick = () => {
      liveScheduleCategory = cat.id;
      renderScheduleChips(liveScheduleData || []);
      renderLiveSchedule(liveScheduleData || []);
    };
    bar.appendChild(chip);
  });
}

const schedQEl = document.getElementById("schedQ");
if (schedQEl) {
  schedQEl.addEventListener("input", (e) => {
    clearTimeout(liveScheduleQueryTimer);
    liveScheduleQueryTimer = setTimeout(() => {
      liveScheduleQuery = (e.target.value || "").trim().toLowerCase();
      if (liveScheduleData) renderLiveSchedule(liveScheduleData);
    }, 200);
  });
}

function renderLiveSchedule(data) {
  // Filter out events whose start time has already passed (today). The
  // schedule is published in UTC HH:MM as documented in LiveModels.kt;
  // compare against the user's current UTC clock so timezone-of-viewer
  // doesn't matter — events disappear when their broadcast starts, not
  // when the user's local time rolls past them.
  const now = new Date();
  const nowMinutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  const todayUtc = now.toLocaleDateString(undefined, {
    weekday: "long", month: "short", day: "numeric", timeZone: "UTC",
  });
  const list = $("#liveScheduleList");
  const status = $("#liveScheduleStatus");
  let liveCount = 0;
  let upcomingCount = 0;

  const q = liveScheduleQuery;
  const cat = liveScheduleCategory;
  // Prep: keep each category, filter its events, drop empty categories.
  const filtered = data.map(g => {
    const groupMeta = classifyScheduleCategory(g.category);
    if (cat !== "all" && groupMeta !== cat) return { ...g, events: [] };
    const events = (g.events || []).filter(e => {
      const m = /^(\d{1,2}):(\d{2})$/.exec(e.time || "");
      if (!m) return true;
      const evMinutes = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
      const stillOnAir = evMinutes >= nowMinutes - 60;
      if (!stillOnAir) return false;
      if (q) {
        const hay = ((e.title || "") + " " + (g.category || "") + " " +
          (e.channels || []).map(c => c.name).join(" ")).toLowerCase();
        if (!hay.includes(q)) return false;
      }
      if (evMinutes <= nowMinutes) liveCount++;
      else upcomingCount++;
      return true;
    });
    return { ...g, events };
  }).filter(g => g.events.length > 0);

  status.textContent = filtered.length
    ? `${todayUtc} (UTC) — ${liveCount} on now, ${upcomingCount} upcoming`
    : `No upcoming events for ${todayUtc} (UTC).`;

  list.innerHTML = "";
  filtered.forEach(cat => {
    const block = document.createElement("div");
    block.className = "schedule-category";
    const head = document.createElement("h3");
    head.textContent = cat.category || "Other";
    block.appendChild(head);
    cat.events.forEach(ev => {
      const row = document.createElement("div");
      row.className = "schedule-event";
      // Channel chips clickable — playing a channel from the schedule
      // does the same as tapping it in the grid.
      const chsHtml = (ev.channels || []).slice(0, 4).map(ch => {
        const safe = (ch.name || ch.id).replace(/['"<>&]/g, "");
        return `<span class="ch-pill" data-cid="${ch.id}">${escapeHtml(safe)}</span>`;
      }).join("");
      row.innerHTML = `
        <span class="when">${escapeHtml(ev.time || "")}</span>
        <span class="what">${escapeHtml(ev.title || "")}</span>
        <span class="chs">${chsHtml}</span>`;
      row.querySelectorAll(".ch-pill").forEach(pill => {
        pill.addEventListener("click", async () => {
          try {
            await post("/api/live/play?id=" + encodeURIComponent(pill.dataset.cid));
            showToast(`Playing ${pill.textContent}`);
          } catch (e) { showToast("Couldn't play"); }
        });
      });
      block.appendChild(row);
    });
    list.appendChild(block);
  });
}

/* ---------- preferences ---------- */
const NETWORK_CHIPS = [
  { id: "netflix", label: "Netflix" },
  { id: "hbo",     label: "HBO"     },
  { id: "disney",  label: "Disney+" },
  { id: "prime",   label: "Prime"   },
  { id: "apple",   label: "Apple TV+" },
  { id: "hulu",    label: "Hulu"    },
];
const LANG_CHIPS = [
  { id: "hi", label: "Hindi" },  { id: "ta", label: "Tamil" },
  { id: "te", label: "Telugu" }, { id: "ml", label: "Malayalam" },
  { id: "ko", label: "Korean" }, { id: "ja", label: "Japanese" },
  { id: "es", label: "Spanish" },{ id: "fr", label: "French" },
  { id: "de", label: "German" }, { id: "zh", label: "Chinese" },
];
let movieGenresCached = null;

async function loadPrefs() {
  try { prefs = await get("/api/me/prefs"); }
  catch (e) { prefs = { networks: [], genres: [], denyLanguages: [] }; }
  renderChipSet($("#prefNetworks"), NETWORK_CHIPS, prefs.networks, (set) =>
    savePrefs({ networks: Array.from(set) }));
  if (!movieGenresCached) {
    try { movieGenresCached = await get("/api/genres"); }
    catch (e) { movieGenresCached = []; }
  }
  renderChipSet(
    $("#prefGenres"),
    movieGenresCached.map(g => ({ id: g.id, label: g.name })),
    prefs.genres,
    (set) => savePrefs({ genres: Array.from(set).map(Number) }),
  );
  renderChipSet($("#prefLangs"), LANG_CHIPS, prefs.denyLanguages, (set) =>
    savePrefs({ denyLanguages: Array.from(set) }), /*deny=*/true);
}

function renderChipSet(host, items, selected, onChange, deny) {
  host.innerHTML = "";
  const sel = new Set((selected || []).map(String));
  items.forEach(it => {
    const chip = document.createElement("div");
    const isOn = sel.has(String(it.id));
    chip.className = "chip" + (deny ? " deny" : "") + (isOn ? " on" : "");
    chip.textContent = it.label;
    chip.onclick = () => {
      if (sel.has(String(it.id))) sel.delete(String(it.id));
      else sel.add(String(it.id));
      chip.classList.toggle("on");
      onChange(sel);
    };
    host.appendChild(chip);
  });
}

let saveTimer;
function savePrefs(patch) {
  Object.assign(prefs, patch);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    const body = new URLSearchParams();
    if (patch.networks)      body.set("networks", patch.networks.join(","));
    if (patch.genres)        body.set("genres", patch.genres.join(","));
    if (patch.denyLanguages) body.set("denyLanguages", patch.denyLanguages.join(","));
    try {
      await post("/api/me/prefs?" + body.toString());
      browseLoaded = false; // re-order favourites on next Browse visit
      toast("Preferences saved");
    } catch (e) {}
  }, 400);
}

/* ---------- polling ---------- */
setInterval(() => {
  if (active === "np")     refresh();
  if (active === "dl")     loadDownloads();
  if (active === "devices") loadDevices();
}, 1500);

boot();
