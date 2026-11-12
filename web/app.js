/* Uplink: one page for tasks, notes, captures and places.
 *
 * The page keeps every record in memory and talks to one endpoint, sync, which takes the
 * records changed here and returns everything changed since the last rev it saw. In a browser
 * that's the hub's POST /api/sync. In the phone app it's the UplinkNative bridge, which
 * answers from the phone's own copy and syncs with the hub in the background. */
"use strict";

const N = window.UplinkNative || null;
const $ = (s, el = document) => el.querySelector(s);
const KEY = "uplink-db-v1";

/* ---- store ---- */

const db = { rev: 0, recs: {}, pending: {} };
try { Object.assign(db, JSON.parse(localStorage.getItem(KEY) || "{}")); } catch (_) {}
const save = () => { try { localStorage.setItem(KEY, JSON.stringify(db)); } catch (_) {} };
const k = (kind, uid) => kind + ":" + uid;
const get = (kind, uid) => { const r = db.recs[k(kind, uid)]; return r && !r.deleted ? r : null; };
const all = (kind) => Object.values(db.recs).filter((r) => r.kind === kind && !r.deleted);
const uid = () => Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, "0")).join("");
const now = () => Math.floor(Date.now() / 1000);

function put(kind, id, data, deleted = false) {
  const cur = db.recs[k(kind, id)];
  const rec = { kind, uid: id, data, deleted, updated_at: Math.max(Date.now(), cur ? cur.updated_at + 1 : 0) };
  db.recs[k(kind, id)] = rec;
  db.pending[k(kind, id)] = rec;
  save();
  render();
  syncSoon(50);
  return rec;
}

let syncing = null, again = false, timer = 0, online = true;
function syncSoon(ms = 0) { clearTimeout(timer); timer = setTimeout(sync, ms); }

async function rawSync(body) {
  if (N) return JSON.parse(N.sync(JSON.stringify(body)));
  const r = await fetch("api/sync", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  if (!r.ok) throw new Error("HTTP " + r.status);
  return r.json();
}

async function sync(wait = 0) {
  if (syncing) { again = true; return syncing; }
  syncing = (async () => {
    const sent = Object.values(db.pending);
    try {
      const res = await rawSync({ since: db.rev, records: sent, wait, device: N ? undefined : "web" });
      for (const r of sent) if (db.pending[k(r.kind, r.uid)] === r) delete db.pending[k(r.kind, r.uid)];
      for (const r of res.records || []) {
        const key = k(r.kind, r.uid), cur = db.recs[key];
        if (!cur || r.updated_at >= cur.updated_at || !db.pending[key]) {
          if (db.pending[key] && db.pending[key].updated_at > r.updated_at) continue;
          db.recs[key] = r;
        }
      }
      db.rev = res.rev;
      online = true;
      save();
    } catch (e) {
      online = false;
    }
    setStatus();
    render();
  })();
  await syncing;
  syncing = null;
  if (again) { again = false; return sync(); }
}

let hubSeq = 0;
const hubWait = {};
window.uplinkHubDone = (id, raw) => { const f = hubWait[id]; delete hubWait[id]; if (f) f(raw); };

async function hub(path, method = "GET", body) {
  if (N) {
    // Asynchronous, so a slow answer doesn't freeze the page.
    const raw = N.hubAsync ? await new Promise((done) => {
      const id = String(++hubSeq);
      hubWait[id] = done;
      N.hubAsync(id, path, method, body ? JSON.stringify(body) : "");
    }) : N.hub(path, method, body ? JSON.stringify(body) : "");
    const res = JSON.parse(raw);
    if (res && res.error) throw new Error(res.error);
    return res;
  }
  const r = await fetch(path, { method, headers: body ? { "content-type": "application/json" } : {}, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || "HTTP " + r.status);
  return j;
}

function setStatus() {
  const el = $("#status");
  const waiting = all("capture").some((c) => (c.data.status || "new") === "new");
  if (!online && !N) { el.textContent = "Offline"; el.hidden = false; }
  else if (waiting) { el.textContent = "Filing"; el.hidden = false; }
  else el.hidden = true;
}

/* ---- dates ---- */

const DAY = 86400;
const pad = (n) => String(n).padStart(2, "0");
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const hm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const WD = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function parseLocal(s) {
  if (!s) return null;
  if (s.length === 10) { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); }
  return new Date(s);
}
function dayWord(d) {
  const t = new Date(); t.setHours(0, 0, 0, 0);
  const dd = new Date(d); dd.setHours(0, 0, 0, 0);
  const diff = Math.round((dd - t) / DAY / 1000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Tomorrow";
  if (diff === -1) return "Yesterday";
  if (diff > 1 && diff < 7) return WD[d.getDay()];
  return `${WD[d.getDay()]} ${d.getDate()} ${d.toLocaleString(undefined, { month: "short" })}`;
}
function dueText(s) {
  const d = parseLocal(s);
  if (!d) return "";
  return s.length > 10 ? `${dayWord(d)} ${hm(d)}` : dayWord(d);
}
function isLate(it) {
  if (it.data.done_at || !it.data.due) return false;
  const d = parseLocal(it.data.due);
  if (it.data.due.length === 10) { d.setHours(23, 59, 59); }
  return d < new Date();
}
function whenText(ts) {
  const d = new Date(ts * 1000);
  const w = dayWord(d);
  return (w === "Today" ? "" : w + " ") + hm(d);
}
function endOfToday() { const d = new Date(); d.setHours(23, 59, 59, 999); return d; }

/* ---- view state ---- */

const ui = { tab: "tasks", list: "all", showDone: false };
try { Object.assign(ui, JSON.parse(localStorage.getItem("uplink-ui") || "{}")); ui.showDone = false; } catch (_) {}
const saveUi = () => localStorage.setItem("uplink-ui", JSON.stringify({ tab: ui.tab, list: ui.list }));

const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function lists() {
  return all("list").sort((a, b) => (b.data.inbox ? 1 : 0) - (a.data.inbox ? 1 : 0) || (a.data.sort || 0) - (b.data.sort || 0) || a.data.name.localeCompare(b.data.name));
}
const inboxUid = () => (lists().find((l) => l.data.inbox) || lists()[0] || {}).uid;

function tasks() { return all("item").filter((i) => i.data.type !== "note"); }

function sortKey(it) {
  const d = it.data;
  const t = d.due ? parseLocal(d.due).getTime() / 1000 : d.remind_at || 9e12;
  return [t, -(d.priority || 0), -(d.created_at || 0)];
}
function cmp(a, b) { const x = sortKey(a), y = sortKey(b); for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; }

function inToday(it) {
  const end = endOfToday();
  const d = it.data;
  return (d.due && parseLocal(d.due) <= end) || (d.remind_at && d.remind_at * 1000 <= end.getTime());
}

/* ---- render ---- */

function render() {
  for (const b of document.querySelectorAll(".tabs button")) b.classList.toggle("on", b.dataset.tab === ui.tab);
  $("#lists").hidden = ui.tab !== "tasks";
  const view = $("#view");
  if (ui.tab === "tasks") renderTasks(view);
  else if (ui.tab === "notes") renderNotes(view);
  else if (ui.tab === "captures") renderCaptures(view);
  else renderPlaces(view);
  setStatus();
}

function renderChips() {
  const ls = lists();
  const chips = [["all", "All"], ["today", "Today"], ...ls.map((l) => [l.uid, l.data.name])];
  if (!chips.some(([id]) => id === ui.list)) ui.list = "all";
  $("#lists").innerHTML = chips.map(([id, name]) =>
    `<button class="chip${id === ui.list ? " on" : ""}" data-list="${esc(id)}">${esc(name)}</button>`).join("") +
    `<button class="chip add" data-list="+">New list</button>`;
}

function taskRow(it) {
  const d = it.data, meta = [];
  if (d.due) meta.push(`<span class="${isLate(it) ? "late" : ""}">${esc(dueText(d.due))}</span>`);
  if (d.remind_at && !d.done_at) meta.push(`<span>Remind ${esc(whenText(d.remind_at))}</span>`);
  if (d.place) {
    const p = get("place", d.place);
    const name = p ? p.data.name : "a place";
    const set = p && p.data.lat != null;
    meta.push(`<span class="${set || d.done_at ? "" : "alert"}">${d.place_on === "leave" ? "Leaving" : "At"} ${esc(name)}${set ? "" : " (not set)"}</span>`);
  }
  if (ui.list === "all" || ui.list === "today") {
    const l = get("list", d.list);
    if (l && !l.data.inbox) meta.push(`<span>${esc(l.data.name)}</span>`);
  }
  if (d.priority >= 3) meta.push("<span>High</span>");
  return `<div class="row${d.done_at ? " done" : ""}" data-item="${esc(it.uid)}">
    <button class="check" data-check="${esc(it.uid)}" aria-label="${d.done_at ? "Mark not done" : "Mark done"}"></button>
    <div class="body"><div class="t">${esc(d.title)}</div>${meta.length ? `<div class="meta">${meta.join("")}</div>` : ""}</div>
  </div>`;
}

function renderTasks(view) {
  renderChips();
  let ts = tasks();
  if (ui.list === "today") ts = ts.filter(inToday);
  else if (ui.list !== "all") ts = ts.filter((t) => t.data.list === ui.list);
  const open = ts.filter((t) => !t.data.done_at).sort(cmp);
  const done = ts.filter((t) => t.data.done_at).sort((a, b) => b.data.done_at - a.data.done_at);
  const title = ui.list === "all" ? "Tasks" : ui.list === "today" ? "Today" : (get("list", ui.list) || { data: { name: "Tasks" } }).data.name;
  $("#title").textContent = title;
  $("#count").textContent = open.length || "";
  let html = "";
  if (!open.length) html += `<p class="empty">${ui.list === "today" ? "Nothing due today" : "All clear"}</p>`;
  else if (ui.list === "all") {
    const placeOnly = open.filter((t) => t.data.place && !t.data.due && !t.data.remind_at);
    const rest = open.filter((t) => !placeOnly.includes(t));
    html += rest.map(taskRow).join("");
    if (placeOnly.length) html += `<div class="group">When you get there</div>` + placeOnly.map(taskRow).join("");
  } else html += open.map(taskRow).join("");
  if (done.length) {
    html += ui.showDone
      ? `<div class="group">Done</div>` + done.slice(0, 200).map(taskRow).join("")
      : `<button class="more" data-act="show-done">Show ${done.length} done</button>`;
  }
  view.innerHTML = html;
}

function renderNotes(view) {
  const ns = all("item").filter((i) => i.data.type === "note").sort((a, b) => (b.data.created_at || 0) - (a.data.created_at || 0));
  $("#title").textContent = "Notes";
  $("#count").textContent = ns.length || "";
  view.innerHTML = ns.length ? ns.map((n) => `<div class="note" data-item="${esc(n.uid)}">
      <div class="t">${esc(n.data.title)}</div>${n.data.notes ? `<div class="n">${esc(n.data.notes)}</div>` : ""}</div>`).join("")
    : `<p class="empty">No notes yet</p>`;
}

const CAP_WORD = { voice: "Voice note", screenshot: "Screenshot", text: "Typed", share: "Shared" };

function shotUrl(c) { return `shots/${encodeURIComponent(c.uid)}.webp`; }

function renderCaptures(view) {
  const cs = all("capture").sort((a, b) => (b.data.created_at || 0) - (a.data.created_at || 0)).slice(0, 300);
  $("#title").textContent = "Captures";
  $("#count").textContent = "";
  view.innerHTML = cs.length ? cs.map((c) => {
    const d = c.data;
    const made = (d.items || []).map((u) => get("item", u)).filter(Boolean);
    const said = d.text || "";
    const img = d.type === "screenshot" && (N ? d.image !== false : d.image_on_hub)
      ? `<img src="${shotUrl(c)}" alt="Screenshot" loading="lazy" data-shot="${esc(c.uid)}">` : "";
    return `<div class="cap">
      <div class="when">${esc(CAP_WORD[d.type] || "Capture")}, ${esc(whenText(d.created_at || 0))}${d.app ? ", " + esc(appName(d.app)) : ""}</div>
      ${(d.status || "new") === "new" ? `<div class="wait">Filing</div>` : d.summary ? `<div class="sum">${esc(d.summary)}</div>` : ""}
      ${said && said !== d.summary ? `<div class="said">${esc(said)}</div>` : ""}
      ${img}
      ${made.length ? `<div class="made">${made.map((i) => `<button data-item="${esc(i.uid)}">${esc(i.data.title)}</button>`).join("")}</div>` : ""}
      <div class="acts"><button data-redo="${esc(c.uid)}">Redo</button><button data-delcap="${esc(c.uid)}">Delete</button></div>
    </div>`;
  }).join("") : `<p class="empty">Press the Essential key to capture the screen, press then hold for a voice note</p>`;
}

function appName(pkg) {
  const known = { "com.android.chrome": "Chrome", "org.mozilla.firefox": "Firefox", "com.google.android.gm": "Gmail", "com.instagram.android": "Instagram", "com.whatsapp": "WhatsApp", "com.google.android.youtube": "YouTube", "com.discord": "Discord" };
  if (known[pkg]) return known[pkg];
  const last = pkg.split(".").pop();
  return last.charAt(0).toUpperCase() + last.slice(1);
}

function renderPlaces(view) {
  const ps = all("place").sort((a, b) => a.data.name.localeCompare(b.data.name));
  $("#title").textContent = "Places";
  $("#count").textContent = "";
  const uses = (p) => tasks().filter((t) => t.data.place === p.uid && !t.data.done_at).length;
  view.innerHTML = ps.map((p) => {
    const n = uses(p), set = p.data.lat != null;
    return `<div class="place" data-place="${esc(p.uid)}"><div class="body"><div>${esc(p.data.name)}</div>
      <div class="meta"><span class="${set ? "" : "alert"}">${set ? esc(p.data.address || "Set") : "Not set"}</span>${n ? `<span>${n} reminder${n > 1 ? "s" : ""}</span>` : ""}</div></div></div>`;
  }).join("") + `<button class="btn wide add-place" data-act="new-place">New place</button>`;
}

/* ---- sheets ---- */

const sheet = $("#sheet");
function openSheet(html, onReady) {
  sheet.innerHTML = `<form class="sheet" method="dialog">${html}</form>`;
  sheet.showModal();
  onReady && onReady(sheet.firstElementChild);
}
sheet.addEventListener("click", (e) => { if (e.target === sheet) sheet.close(); });

function seg(name, options, value) {
  return `<div class="seg" data-seg="${name}">${options.map(([v, label]) =>
    `<button type="button" data-v="${esc(v)}" class="${String(v) === String(value) ? "on" : ""}">${esc(label)}</button>`).join("")}</div>`;
}
function segValue(form, name) { const b = form.querySelector(`[data-seg="${name}"] .on`); return b ? b.dataset.v : ""; }
sheet.addEventListener("click", (e) => {
  const b = e.target.closest(".seg button");
  if (!b) return;
  for (const o of b.parentElement.children) o.classList.toggle("on", o === b);
  b.parentElement.dispatchEvent(new Event("change", { bubbles: true }));
});

function grow(t) { t.style.height = "auto"; t.style.height = t.scrollHeight + "px"; }

function toLocalInput(ts) { const d = new Date(ts * 1000); return `${ymd(d)}T${hm(d)}`; }

function editItem(id) {
  const it = get("item", id);
  if (!it) return;
  const d = it.data;
  const isNote = d.type === "note";
  const ls = lists(), ps = all("place").sort((a, b) => a.data.name.localeCompare(b.data.name));
  const dueDate = d.due ? d.due.slice(0, 10) : "", dueTime = d.due && d.due.length > 10 ? d.due.slice(11, 16) : "";
  const cap = d.capture && get("capture", d.capture);
  openSheet(`
    <textarea class="big" name="title" rows="1" aria-label="Title">${esc(d.title)}</textarea>
    <textarea class="notes" name="notes" placeholder="Notes" aria-label="Notes">${esc(d.notes || "")}</textarea>
    <div class="field"><span>Kind</span>${seg("type", [["task", "Task"], ["note", "Note"]], isNote ? "note" : "task")}</div>
    <div class="tonly" ${isNote ? "hidden" : ""}>
      <div class="fields">
      <div class="field"><span>List</span><select name="list">${ls.map((l) => `<option value="${esc(l.uid)}" ${l.uid === d.list ? "selected" : ""}>${esc(l.data.name)}</option>`).join("")}</select></div>
      <div class="field"><span>Due</span><div class="pair two"><input type="date" name="due" value="${dueDate}"><input type="time" name="duetime" value="${dueTime}" aria-label="Due time"></div></div>
      <div class="field"><span>Remind</span><input type="datetime-local" name="remind" value="${d.remind_at ? toLocalInput(d.remind_at) : ""}"></div>
      <div class="field"><span>Place</span><div class="stack"><select name="place"><option value="">None</option>${ps.map((p) => `<option value="${esc(p.uid)}" ${p.uid === d.place ? "selected" : ""}>${esc(p.data.name)}</option>`).join("")}</select>
        <div class="on-seg" ${d.place ? "" : "hidden"}>${seg("place_on", [["arrive", "Arrive"], ["leave", "Leave"]], d.place_on || "arrive")}</div></div></div>
      <div class="field"><span>Priority</span>${seg("priority", [[0, "None"], [1, "Low"], [2, "Mid"], [3, "High"]], d.priority || 0)}</div>
      </div>
    </div>
    ${cap ? `<button type="button" class="x" data-act="to-capture" style="text-align:left">From a ${esc((CAP_WORD[cap.data.type] || "capture").toLowerCase())}, ${esc(whenText(cap.data.created_at || 0))}</button>` : ""}
    <div class="actions"><button type="button" class="btn danger" data-act="delete">Delete</button>
      <button type="submit" class="btn primary" value="save">Save</button></div>`, (f) => {
    grow(f.title);
    f.title.addEventListener("input", () => grow(f.title));
    f.querySelector('[data-seg="type"]').addEventListener("change", () => { f.querySelector(".tonly").hidden = segValue(f, "type") === "note"; });
    f.place.addEventListener("change", () => { f.querySelector(".on-seg").hidden = !f.place.value; });
    f.querySelector('[data-act="delete"]').onclick = () => { put("item", id, d, true); sheet.close(); toast("Deleted"); };
    const tc = f.querySelector('[data-act="to-capture"]');
    if (tc) tc.onclick = () => { sheet.close(); ui.tab = "captures"; saveUi(); render(); };
    f.onsubmit = () => {
      const nd = { ...d };
      nd.title = f.title.value.trim() || "Untitled";
      nd.notes = f.notes.value;
      nd.type = segValue(f, "type");
      if (nd.type === "task") {
        nd.list = f.list.value || inboxUid();
        nd.due = f.due.value ? (f.duetime.value ? `${f.due.value}T${f.duetime.value}` : f.due.value) : null;
        const r = f.remind.value ? Math.floor(new Date(f.remind.value).getTime() / 1000) : null;
        if (r !== nd.remind_at) { nd.remind_at = r; delete nd.fired_at; }
        const pl = f.place.value || null, on = segValue(f, "place_on");
        if (pl !== (nd.place || null) || on !== nd.place_on) delete nd.place_fired_at;
        nd.place = pl; nd.place_on = pl ? on : null;
        nd.priority = Number(segValue(f, "priority"));
      }
      put("item", id, nd);
    };
  });
}

function editList(id) {
  const l = id ? get("list", id) : null;
  openSheet(`
    <input type="text" name="name" placeholder="List name" value="${esc(l ? l.data.name : "")}" aria-label="List name" required>
    <div class="actions">${l && !l.data.inbox ? `<button type="button" class="btn danger" data-act="delete">Delete list</button>` : "<span></span>"}
      <button type="submit" class="btn primary">${l ? "Save" : "Add list"}</button></div>`, (f) => {
    f.name.focus();
    const del = f.querySelector('[data-act="delete"]');
    if (del) del.onclick = () => {
      const items = tasks().filter((t) => t.data.list === id);
      if (items.length && !confirm(`Delete ${l.data.name} and its ${items.length} task${items.length > 1 ? "s" : ""}?`)) return;
      for (const t of items) put("item", t.uid, t.data, true);
      put("list", id, l.data, true);
      ui.list = "all"; saveUi(); sheet.close();
    };
    f.onsubmit = () => {
      const name = f.name.value.trim();
      if (!name) return;
      if (l) put("list", id, { ...l.data, name });
      else { const nid = uid(); put("list", nid, { name, sort: lists().length }); ui.list = nid; saveUi(); }
    };
  });
}

/* A rough location for address search: asked once, kept for 10 minutes, never blocking for long. */
let nearP = null;
function nearHere() {
  if (!nearP) nearP = new Promise((res) => {
    if (!navigator.geolocation) return res(null);
    navigator.geolocation.getCurrentPosition((p) => res([p.coords.latitude, p.coords.longitude]), () => res(null),
      { enableHighAccuracy: false, maximumAge: 600000, timeout: 6000 });
  }).then((pt) => { if (!pt) setTimeout(() => { nearP = null; }, 30000); return pt; });
  return nearP;
}

function km(n) { return n == null ? "" : n < 1 ? `${Math.round(n * 1000)} m` : `${n < 10 ? n.toFixed(1) : Math.round(n)} km`; }

function editPlace(id) {
  const p = id ? get("place", id) : null;
  const d = p ? { ...p.data } : { name: "", radius: 150 };
  const where = () => d.lat != null ? (d.address || `${d.lat.toFixed(5)}, ${d.lon.toFixed(5)}`) : "Not set";
  openSheet(`
    <input type="text" class="big" name="name" placeholder="Name, like Home" value="${esc(d.name)}" aria-label="Place name" required>
    <div class="fields">
      <div class="field"><span>Where</span><span class="where ${d.lat != null ? "" : "unset"}">${esc(where())}</span></div>
      <div class="field"><span></span><div class="stack">
        <input type="search" name="q" placeholder="Search an address" aria-label="Search an address" enterkeyhint="search">
        <div class="results"></div>
        <button type="button" class="btn wide" data-act="here">Use where I am</button>
      </div></div>
      <div class="field"><span>Radius</span>${seg("radius", [[100, "100 m"], [150, "150 m"], [300, "300 m"], [600, "600 m"]], d.radius || 150)}</div>
    </div>
    <div class="actions">${p ? `<button type="button" class="btn danger" data-act="delete">Delete</button>` : "<span></span>"}
      <button type="submit" class="btn primary">Save</button></div>`, (f) => {
    if (!d.name) f.name.focus();
    const show = () => { const w = f.querySelector(".where"); w.textContent = where(); w.classList.toggle("unset", d.lat == null); };
    const out = f.querySelector(".results");
    f.querySelector('[data-act="here"]').onclick = (e) => {
      const b = e.currentTarget;
      b.textContent = "Finding you";
      if (!navigator.geolocation) { b.textContent = "Location unavailable"; return; }
      navigator.geolocation.getCurrentPosition((pos) => {
        d.lat = pos.coords.latitude; d.lon = pos.coords.longitude; d.address = "Where you were at " + hm(new Date());
        b.textContent = "Use where I am"; show();
      }, (err) => { b.textContent = err.code === 1 ? "Allow location first" : "Couldn't find you"; }, { enableHighAccuracy: true, timeout: 20000 });
    };
    let t = 0, seq = 0;
    nearHere();
    f.q.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); f.q.dispatchEvent(new Event("input")); } });
    f.q.addEventListener("input", () => {
      clearTimeout(t);
      const q = f.q.value.trim();
      if (q.length < 3) { out.innerHTML = ""; return; }
      t = setTimeout(async () => {
        const mine = ++seq;
        out.innerHTML = `<p class="x">Searching</p>`;
        const pt = await nearHere();
        try {
          const res = await hub("api/geocode?q=" + encodeURIComponent(q) + (pt ? `&lat=${pt[0].toFixed(2)}&lon=${pt[1].toFixed(2)}` : ""));
          if (mine !== seq) return;
          out.innerHTML = res.map((r, i) => `<button type="button" data-i="${i}"><span>${esc(r.name)}</span><span class="km">${km(r.km)}</span></button>`).join("") || `<p class="x">No match. Try the street and town</p>`;
          for (const b of out.querySelectorAll("button")) b.onclick = () => {
            const r = res[Number(b.dataset.i)];
            d.lat = r.lat; d.lon = r.lon; d.address = r.name;
            out.innerHTML = ""; f.q.value = ""; show();
          };
        } catch (e) { if (mine === seq) out.innerHTML = `<p class="x">Search needs the hub</p>`; }
      }, 700);
    });
    const del = f.querySelector('[data-act="delete"]');
    if (del) del.onclick = () => { put("place", id, d, true); sheet.close(); };
    f.onsubmit = (e) => {
      d.name = f.name.value.trim();
      if (!d.name) { e.preventDefault(); return; }
      d.radius = Number(segValue(f, "radius")) || 150;
      put("place", id || uid(), d);
    };
  });
}

function showShot(id) {
  const c = get("capture", id);
  openSheet(`<img class="shot-full" src="${shotUrl(c)}" alt="Screenshot">
    ${c.data.screen_text ? `<details><summary class="x">Text on screen</summary><p class="said" style="white-space:pre-wrap;color:var(--dim);font-size:14px">${esc(c.data.screen_text)}</p></details>` : ""}
    <div class="actions"><span></span><button class="btn">Close</button></div>`);
}

/* ---- capture bar ---- */

$("#capture").addEventListener("submit", async (e) => {
  e.preventDefault();
  const text = $("#say").value.trim();
  if (!text) return;
  $("#say").value = "";
  const id = uid();
  const before = new Set(all("item").map((i) => i.uid));
  db.recs[k("capture", id)] = db.pending[k("capture", id)] = {
    kind: "capture", uid: id, deleted: false, updated_at: Date.now(),
    data: { type: "text", text, status: "new", created_at: now(), device: N ? "phone" : "web" },
  };
  save();
  setStatus();
  await sync(20);
  if (N) await waitFiled(id);
  const c = get("capture", id);
  if (c && c.data.status === "done") {
    const made = (c.data.items || []).filter((u) => !before.has(u)).map((u) => get("item", u)).filter(Boolean);
    toast(made.length ? "Added " + made.map((m) => m.data.title).join(", ") : "Saved");
  } else toast(online ? "Saved, filing soon" : "Saved, sends when online");
});

async function waitFiled(id) {
  for (let i = 0; i < 25; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    await sync();
    const c = get("capture", id);
    if (c && c.data.status === "done") return;
  }
}

let toastT = 0;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toastT);
  toastT = setTimeout(() => t.classList.remove("show"), 2600);
}

/* ---- events ---- */

document.addEventListener("click", async (e) => {
  const t = e.target;
  const tab = t.closest(".tabs button");
  if (tab) { ui.tab = tab.dataset.tab; saveUi(); window.scrollTo(0, 0); render(); return; }
  const chip = t.closest("[data-list]");
  if (chip) {
    const id = chip.dataset.list;
    if (id === "+") return editList(null);
    if (id === ui.list && id !== "all" && id !== "today") return editList(id);
    ui.list = id; ui.showDone = false; saveUi(); render(); return;
  }
  const check = t.closest("[data-check]");
  if (check) {
    const it = get("item", check.dataset.check);
    if (!it) return;
    const done = !it.data.done_at;
    check.classList.add("pop");
    setTimeout(() => put("item", it.uid, { ...it.data, done_at: done ? now() : null }), done ? 280 : 0);
    if (done) toast("Done");
    return;
  }
  const shot = t.closest("[data-shot]");
  if (shot) return showShot(shot.dataset.shot);
  const item = t.closest("[data-item]");
  if (item && !sheet.open) return editItem(item.dataset.item);
  if (item && sheet.open) { sheet.close(); return editItem(item.dataset.item); }
  const place = t.closest("[data-place]");
  if (place) return editPlace(place.dataset.place);
  const redo = t.closest("[data-redo]");
  if (redo) {
    redo.textContent = "Filing";
    try { await hub("api/reprocess/" + redo.dataset.redo, "POST"); await sync(); toast("Filed again"); }
    catch (err) { redo.textContent = "Redo"; toast("Redo needs the hub"); }
    return;
  }
  const delcap = t.closest("[data-delcap]");
  if (delcap) { const c = get("capture", delcap.dataset.delcap); if (c) put("capture", c.uid, c.data, true); return; }
  const act = t.closest("[data-act]");
  if (act && act.dataset.act === "show-done") { ui.showDone = true; render(); return; }
  if (act && act.dataset.act === "new-place") return editPlace(null);
});

if (N) {
  const s = $("#settings");
  s.hidden = false;
  s.onclick = () => N.openSettings();
  // The phone app calls this when its own copy changed (a capture filed, a sync landed).
  window.uplinkChanged = () => sync();
}

document.addEventListener("visibilitychange", () => { if (!document.hidden) sync(); });
setInterval(() => { if (!document.hidden) sync(); }, N ? 5000 : 20000);
render();
sync();
