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
  // Superuser-only Devices view: moved out of the bottom tabbar (which was
  // overcrowded at 7 items) into a gear-button on the topbar.
  if (me.role === "SUPERUSER") {
    $("#devicesBtn").classList.remove("hidden"); loadDevices();
  } else { $("#devicesBtn").classList.add("hidden"); }
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
  if (name === "browse" && !browseLoaded) loadBrowse();
  if (name === "live" && !liveLoaded) loadLive();
  if (name === "prefs") loadPrefs();
}
$$(".tab").forEach(b => b.onclick = () => selectTab(b.dataset.pane));

// Topbar Devices shortcut (superuser only — visibility set in fetchMe()).
const devicesBtn = $("#devicesBtn");
if (devicesBtn) devicesBtn.onclick = () => selectTab("devices");

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
    return;
  }
  renderLiveSchedule(data);
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

  // Prep: keep each category, filter its events, drop empty categories.
  const filtered = data.map(cat => {
    const events = (cat.events || []).filter(e => {
      const m = /^(\d{1,2}):(\d{2})$/.exec(e.time || "");
      if (!m) return true;
      const evMinutes = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
      // Keep events that haven't started yet OR are within the last hour
      // (assume those are still airing).
      const stillOnAir = evMinutes >= nowMinutes - 60;
      if (stillOnAir) {
        if (evMinutes <= nowMinutes) liveCount++;
        else upcomingCount++;
      }
      return stillOnAir;
    });
    return { ...cat, events };
  }).filter(cat => cat.events.length > 0);

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
