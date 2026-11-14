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

/* ---- repeats ----
 * `repeat` is an iCalendar RRULE. Marking a repeating task done moves it to its next time.
 * This is the same walk as roll() in server/uplink.py and Reminders.kt; keep them in step. */

const FREQS = ["MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY"];
const BYDAY = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];
const monIdx = (t) => (t.getDay() + 6) % 7;

function parseRule(s) {
  if (!s) return null;
  const parts = {};
  for (const p of String(s).toUpperCase().replace(/^RRULE:/, "").split(";")) {
    const [key, v = ""] = p.split("=");
    if (key.trim()) parts[key.trim()] = v.trim();
  }
  if (!FREQS.includes(parts.FREQ)) return null;
  const interval = Math.min(100000, Math.max(1, parseInt(parts.INTERVAL, 10) || 1));
  const byday = [...new Set((parts.BYDAY || "").split(",").map((d) => BYDAY.indexOf(d.slice(-2))).filter((i) => i >= 0))].sort();
  const count = /^\d+$/.test(parts.COUNT || "") ? Number(parts.COUNT) : null;
  return { freq: parts.FREQ, interval, byday, count, until: parseUntil(parts.UNTIL), parts };
}
function parseUntil(v) {
  let m = /^(\d{4})(\d\d)(\d\d)$/.exec(v || "");
  if (m) return new Date(+m[1], m[2] - 1, +m[3], 23, 59, 59);
  m = /^(\d{4})(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)(Z?)$/.exec(v || "");
  if (!m) return null;
  const a = [+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]];
  return m[7] ? new Date(Date.UTC(...a)) : new Date(...a);
}
function formatRule(r, change) {
  const parts = { ...r.parts };
  for (const [key, v] of Object.entries(change)) { if (v == null) delete parts[key]; else parts[key] = String(v); }
  const order = ["FREQ", "INTERVAL", "BYDAY", "COUNT", "UNTIL"];
  const keys = [...order.filter((x) => x in parts), ...Object.keys(parts).filter((x) => !order.includes(x))];
  return keys.map((x) => `${x}=${parts[x]}`).join(";");
}

function* occurrences(a, r, after) {
  const { freq: f, interval: n, byday: days } = r;
  const ok = (i, t) => (r.count == null || i < r.count) && (!r.until || t <= r.until);
  const Y = a.getFullYear(), M = a.getMonth(), D = a.getDate(), h = a.getHours(), mi = a.getMinutes(), s = a.getSeconds();
  yield [0, a];
  if (f === "WEEKLY" && days.length) {
    const mon = D - monIdx(a);
    let i = 0;
    for (let w = 0; w < 100000; w++) for (const d of days) {
      const t = new Date(Y, M, mon + w * 7 * n + d, h, mi, s);
      if (t <= a) continue;
      if (!ok(++i, t)) return;
      yield [i, t];
    }
    return;
  }
  const step = f === "MINUTELY" ? 60e3 * n : f === "HOURLY" ? 3600e3 * n : 0;
  let k = 1;
  if (step && after && !days.length && after > a) k = Math.max(1, Math.floor((after - a) / step));
  let i = k - 1;
  for (const end = k + 200000; k < end; k++) {
    let t;
    if (step) t = new Date(a.getTime() + k * step);
    else if (f === "DAILY" || f === "WEEKLY") t = new Date(Y, M, D + k * n * (f === "WEEKLY" ? 7 : 1), h, mi, s);
    else {
      const m = M + k * n * (f === "YEARLY" ? 12 : 1);
      t = new Date(Y, m, Math.min(D, new Date(Y, m + 1, 0).getDate()), h, mi, s);
    }
    if (days.length && f !== "MONTHLY" && f !== "YEARLY" && !days.includes(monIdx(t))) continue;
    if (!ok(++i, t)) return;
    yield [i, t];
  }
}
function anchorOf(d) {
  if (d.remind_at) return new Date(d.remind_at * 1000);
  if (!d.due) return null;
  const t = parseLocal(d.due);
  if (d.due.length === 10) t.setHours(9, 0, 0, 0);
  return t;
}
const wallMs = (t) => Date.UTC(t.getFullYear(), t.getMonth(), t.getDate(), t.getHours(), t.getMinutes(), t.getSeconds());
const wallStr = (ms) => { const t = new Date(ms); return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}T${pad(t.getUTCHours())}:${pad(t.getUTCMinutes())}`; };

/* A repeating task just marked done, moved on and open again; null if it doesn't repeat or is over. */
function roll(d) {
  const r = parseRule(d.repeat);
  if (!r || !d.done_at || d.type === "note") return null;
  const nd = { ...d, done_at: null };
  delete nd.place_fired_at;
  const a = anchorOf(d);
  if (!a) return nd;
  const floor = new Date(Math.max(Date.now(), a.getTime()));
  let next = null;
  for (const [i, t] of occurrences(a, r, floor)) if (i > 0 && t > floor) { next = [i, t]; break; }
  if (!next) return null;
  const [i, when] = next;
  if (r.count != null) nd.repeat = formatRule(r, { COUNT: r.count - i });
  if (d.remind_at) nd.remind_at = Math.floor(when.getTime() / 1000);
  if (d.due) {
    const shift = wallMs(when) - wallMs(a);
    const days = Math.round((Date.UTC(when.getFullYear(), when.getMonth(), when.getDate()) - Date.UTC(a.getFullYear(), a.getMonth(), a.getDate())) / 864e5);
    if (d.due.length === 10) nd.due = wallStr(wallMs(parseLocal(d.due)) + days * 864e5).slice(0, 10);
    else nd.due = wallStr(wallMs(parseLocal(d.due)) + shift);
  }
  return nd;
}

function repeatText(s) {
  const r = parseRule(s);
  if (!r) return "";
  const n = r.interval;
  let t;
  if (r.freq === "WEEKLY" && r.byday.length) {
    t = r.byday.join() === "0,1,2,3,4" && n === 1 ? "Weekdays"
      : `Every ${n > 1 ? n + " weeks, " : ""}${r.byday.map((d) => WD[(d + 1) % 7]).join(", ")}`;
  } else if (r.freq === "MINUTELY") {
    t = n < 60 ? `Every ${n > 1 ? n + " " : ""}min` : `Every ${Math.floor(n / 60)} h${n % 60 ? ` ${n % 60} min` : ""}`;
  } else if (r.freq === "HOURLY") t = n > 1 ? `Every ${n} h` : "Every hour";
  else {
    const unit = { DAILY: "day", WEEKLY: "week", MONTHLY: "month", YEARLY: "year" }[r.freq];
    t = n > 1 ? `Every ${n} ${unit}s` : `Every ${unit}`;
  }
  if (r.until) t += `, until ${dayWord(r.until)}`;
  else if (r.count != null) t += `, ${r.count} left`;
  return t;
}

/* ---- view state ---- */

// ui.list is "upcoming", "categories", "lists" or a list's uid.
const ui = { tab: "tasks", list: "upcoming", showDone: false };
try { Object.assign(ui, JSON.parse(localStorage.getItem("uplink-ui") || "{}")); ui.showDone = false; } catch (_) {}
const saveUi = () => localStorage.setItem("uplink-ui", JSON.stringify({ tab: ui.tab, list: ui.list }));
const inList = () => !["upcoming", "categories", "lists"].includes(ui.list);

const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function lists() {
  return all("list").sort((a, b) => (b.data.inbox ? 1 : 0) - (a.data.inbox ? 1 : 0) || (a.data.sort || 0) - (b.data.sort || 0) || a.data.name.localeCompare(b.data.name));
}
const inboxUid = () => (lists().find((l) => l.data.inbox) || lists()[0] || {}).uid;

function tasks() { return all("item").filter((i) => i.data.type !== "note"); }

/* When a task comes up: a late due date, else the earliest of due and reminder from today on,
 * else a past reminder. day is in local calendar days from today; a date-only due sorts at the
 * end of its day, after that day's timed tasks. */
function dayOff(d) { const t = new Date(); t.setHours(0, 0, 0, 0); const x = new Date(d); x.setHours(0, 0, 0, 0); return Math.round((x - t) / DAY / 1000); }
function whenOf(it) {
  const d = it.data, c = [];
  if (d.due) { const x = parseLocal(d.due), t = d.due.length === 10 ? new Date(x).setHours(23, 59, 59, 999) : x.getTime(); c.push({ t, day: dayOff(x) }); }
  if (d.remind_at) c.push({ t: d.remind_at * 1000, day: dayOff(d.remind_at * 1000) });
  if (d.due && c[0].day < 0) return c[0];
  const ahead = c.filter((w) => w.day >= 0).sort((a, b) => a.t - b.t);
  return ahead[0] || c[0] || null;
}
function byWhen(a, b) {
  const x = whenOf(a), y = whenOf(b);
  return (x ? x.t : 9e15) - (y ? y.t : 9e15) || (b.data.priority || 0) - (a.data.priority || 0) || (b.data.created_at || 0) - (a.data.created_at || 0);
}
function byPriority(a, b) {
  const order = lists().map((l) => l.uid), li = (t) => { const i = order.indexOf(t.data.list); return i < 0 ? 1e6 : i; };
  return (b.data.priority || 0) - (a.data.priority || 0) || li(a) - li(b) || (b.data.created_at || 0) - (a.data.created_at || 0);
}

/* The hub picks a category for each open task (see Categorizer in the hub); not judged yet is Other. */
const CATS = [["work", "Work"], ["chores", "Chores"], ["projects", "Projects"], ["fun", "Fun"], ["other", "Other"]];
const catLabel = (c) => (CATS.find(([v]) => v === c) || [])[1];
const catOf = (it) => catLabel(it.data.category) ? it.data.category : "other";

const SECTIONS = [["late", "Late"], ["today", "Today"], ["tomorrow", "Tomorrow"], ["week", "This week"], ["later", "Later"], ["there", "When you get there"], ["any", "Anytime"]];
function sectionOf(it) {
  const w = whenOf(it), d = it.data;
  if (!w) return d.place ? "there" : "any";
  return w.day < 0 ? "late" : w.day === 0 ? "today" : w.day === 1 ? "tomorrow" : w.day < 7 ? "week" : "later";
}

/* ---- render ---- */

function render() {
  for (const b of document.querySelectorAll(".tabs button")) b.classList.toggle("on", b.dataset.tab === ui.tab);
  $("#views").hidden = ui.tab !== "tasks";
  const view = $("#view");
  if (ui.tab === "tasks") renderTasks(view);
  else if (ui.tab === "notes") renderNotes(view);
  else if (ui.tab === "captures") renderCaptures(view);
  else renderPlaces(view);
  setStatus();
}

/* A date or time, shortened when the section header already says the day (mode: today, tomorrow, week). */
function timeText(d, timed, mode) {
  const off = dayOff(d);
  if ((mode === "today" && off === 0) || (mode === "tomorrow" && off === 1)) return timed ? hm(d) : "";
  if (mode === "week" && off > 1 && off < 7) return WD[d.getDay()] + (timed ? " " + hm(d) : "");
  return timed ? `${dayWord(d)} ${hm(d)}` : dayWord(d);
}

function taskRow(it, mode = "", showList = false) {
  const d = it.data, meta = [];
  if (d.due) { const x = timeText(parseLocal(d.due), d.due.length > 10, mode); if (x) meta.push(`<span class="${isLate(it) ? "late" : ""}">${esc(x)}</span>`); }
  if (d.remind_at && !d.done_at) meta.push(`<span>Remind ${esc(timeText(new Date(d.remind_at * 1000), true, mode))}</span>`);
  if (d.repeat && !d.done_at) meta.push(`<span>${esc(repeatText(d.repeat) || "Repeats")}</span>`);
  if (d.place) {
    const p = get("place", d.place);
    const name = p ? p.data.name : "a place";
    const set = p && p.data.lat != null;
    meta.push(`<span class="${set || d.done_at ? "" : "alert"}">${d.place_on === "leave" ? "Leaving" : "At"} ${esc(name)}${set ? "" : " (not set)"}</span>`);
  }
  if (showList) {
    const l = get("list", d.list);
    if (l && !l.data.inbox) meta.push(`<span>${esc(l.data.name)}</span>`);
  }
  if (d.priority >= 3) meta.push("<span>High</span>");
  return `<div class="row${d.done_at ? " done" : ""}" data-item="${esc(it.uid)}">
    <button class="check" data-check="${esc(it.uid)}" aria-label="${d.done_at ? "Mark not done" : "Mark done"}"></button>
    <div class="body"><div class="t">${esc(d.title)}</div>${meta.length ? `<div class="meta">${meta.join("")}</div>` : ""}</div>
  </div>`;
}

function doneHtml(done, showList) {
  if (!done.length) return "";
  return ui.showDone
    ? `<h2 class="group">Done</h2>` + done.sort((a, b) => b.data.done_at - a.data.done_at).slice(0, 200).map((t) => taskRow(t, "", showList)).join("")
    : `<button class="more" data-act="show-done">Show ${done.length} done</button>`;
}

function renderTasks(view) {
  if (inList() && !get("list", ui.list)) ui.list = "upcoming";
  const ts = tasks(), open = ts.filter((t) => !t.data.done_at);
  const nav = $("#views");
  let html = "";
  if (inList()) {
    const l = get("list", ui.list);
    const mine = ts.filter((t) => t.data.list === ui.list), mo = mine.filter((t) => !t.data.done_at).sort(byWhen);
    $("#title").textContent = l.data.name;
    $("#count").textContent = mo.length || "";
    nav.innerHTML = `<button class="back" data-view="lists"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M15.4 6.4 14 5l-7 7 7 7 1.4-1.4L9.8 12z"/></svg>Lists</button>
      <button class="back" data-act="edit-list">Edit</button>`;
    html = (mo.length ? mo.map((t) => taskRow(t)).join("") : `<p class="empty">All clear</p>`) + doneHtml(mine.filter((t) => t.data.done_at), false);
  } else {
    $("#title").textContent = "Tasks";
    $("#count").textContent = open.length || "";
    nav.innerHTML = `<div class="switch">${[["upcoming", "Upcoming"], ["categories", "Categories"], ["lists", "Lists"]].map(([v, label]) =>
      `<button data-view="${v}" class="${ui.list === v ? "on" : ""}" aria-pressed="${ui.list === v}">${label}</button>`).join("")}</div>`;
    if (ui.list === "lists") {
      const empty = [];
      for (const l of lists()) {
        const mo = open.filter((t) => t.data.list === l.uid).sort(byWhen);
        if (!mo.length) { empty.push(l); continue; }
        html += `<section><h2 class="group lh"><button class="head" data-open="${esc(l.uid)}">${esc(l.data.name)}<span>${mo.length}</span></button></h2>${mo.map((t) => taskRow(t)).join("")}</section>`;
      }
      const known = new Set(lists().map((l) => l.uid)), stray = open.filter((t) => !known.has(t.data.list)).sort(byWhen);
      if (stray.length) html += `<section><h2 class="group"><span class="head">No list<span>${stray.length}</span></span></h2>${stray.map((t) => taskRow(t)).join("")}</section>`;
      if (empty.length) html += `<div class="idle">${empty.map((l) => `<button data-open="${esc(l.uid)}">${esc(l.data.name)}</button>`).join("")}</div>`;
      html += `<button class="btn wide add-list" data-act="new-list">New list</button>`;
    } else if (ui.list === "categories") {
      const by = {};
      for (const t of open) (by[catOf(t)] ||= []).push(t);
      for (const [key, label] of CATS) {
        const xs = by[key];
        if (!xs) continue;
        html += `<section><h2 class="group"><span class="head">${label}<span>${xs.length}</span></span></h2>${xs.sort(byWhen).map((t) => taskRow(t, "", true)).join("")}</section>`;
      }
      if (!open.length) html = `<p class="empty">All clear</p>`;
    } else {
      const by = {};
      for (const t of open) (by[sectionOf(t)] ||= []).push(t);
      for (const [key, label] of SECTIONS) {
        const xs = by[key];
        if (!xs) continue;
        xs.sort(key === "any" || key === "there" ? byPriority : byWhen);
        html += `<section><h2 class="group${key === "late" ? " alert" : ""}">${label}</h2>${xs.map((t) => taskRow(t, key, true)).join("")}</section>`;
      }
      if (!open.length) html = `<p class="empty">All clear</p>`;
      html += doneHtml(ts.filter((t) => t.data.done_at), true);
    }
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
    const word = d.type === "screenshot" && d.voice ? "Screenshot with voice note" : CAP_WORD[d.type] || "Capture";
    return `<div class="cap">
      <div class="when">${esc(word)}, ${esc(whenText(d.created_at || 0))}${d.app ? ", " + esc(appName(d.app)) : ""}</div>
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
      <div class="meta"><span class="${set ? "" : "alert"}">${set ? esc(placeAddr(p.data)) : "Not set"}</span>${n ? `<span>${n} reminder${n > 1 ? "s" : ""}</span>` : ""}</div></div></div>`;
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
  if (b.parentElement.classList.contains("multi")) b.classList.toggle("on");
  else for (const o of b.parentElement.children) o.classList.toggle("on", o === b);
  b.parentElement.dispatchEvent(new Event("change", { bubbles: true }));
});

function grow(t) { t.style.height = "auto"; t.style.height = t.scrollHeight + "px"; }

function toLocalInput(ts) { const d = new Date(ts * 1000); return `${ymd(d)}T${hm(d)}`; }

/* Quick picks for the due date: today, tomorrow, the coming Saturday, the coming Monday. */
const inDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return d; };
const QUICK = [["Today", () => inDays(0)], ["Tomorrow", () => inDays(1)],
  ["Weekend", () => inDays((6 - new Date().getDay() + 7) % 7)], ["Next week", () => inDays((8 - new Date().getDay()) % 7 || 7)]];

function editItem(id) {
  const it = get("item", id);
  if (!it) return;
  const d = it.data;
  const isNote = d.type === "note";
  const ls = lists(), ps = all("place").sort((a, b) => a.data.name.localeCompare(b.data.name));
  const dueDate = d.due ? d.due.slice(0, 10) : "", dueTime = d.due && d.due.length > 10 ? d.due.slice(11, 16) : "";
  const cap = d.capture && get("capture", d.capture);
  // "" is Auto: the hub picks. A category without category_auto is the person's own pick.
  const cat0 = catLabel(d.category) && !d.category_auto ? d.category : "";
  const autoLabel = catLabel(d.category) && d.category_auto ? `Auto: ${catLabel(d.category)}` : "Auto";
  const rule = parseRule(d.repeat);
  let endsInit = rule && rule.until ? ymd(rule.until) : "";
  if (rule && !rule.until && rule.count != null && anchorOf(d)) {
    let last = null;
    for (const [, t] of occurrences(anchorOf(d), rule)) last = t;
    endsInit = ymd(last);
  }
  openSheet(`
    <textarea class="big" name="title" rows="1" aria-label="Title">${esc(d.title)}</textarea>
    <textarea class="notes" name="notes" placeholder="Notes" aria-label="Notes">${esc(d.notes || "")}</textarea>
    <div class="field"><span>Kind</span>${seg("type", [["task", "Task"], ["note", "Note"]], isNote ? "note" : "task")}</div>
    <div class="tonly" ${isNote ? "hidden" : ""}>
      <div class="fields">
      <div class="field"><span>List</span><select name="list">${ls.map((l) => `<option value="${esc(l.uid)}" ${l.uid === d.list ? "selected" : ""}>${esc(l.data.name)}</option>`).join("")}</select></div>
      <div class="field"><span>Category</span><select name="category"><option value="">${esc(autoLabel)}</option>${CATS.map(([v, label]) => `<option value="${v}" ${v === cat0 ? "selected" : ""}>${label}</option>`).join("")}</select></div>
      <div class="field"><span>Due</span><div class="pair two"><input type="date" name="due" value="${dueDate}"><input type="time" name="duetime" value="${dueTime}" aria-label="Due time"></div></div>
      ${seg("quick", QUICK.map(([label, f]) => [ymd(f()), label]), dueDate)}
      <div class="field"><span>Remind</span><input type="datetime-local" name="remind" value="${d.remind_at ? toLocalInput(d.remind_at) : ""}"></div>
      <div class="field"><span>Repeat</span><div class="stack">
        <div class="pair rep"><input type="number" name="rep_n" min="1" max="100000" inputmode="numeric" value="${rule ? rule.interval : 1}" aria-label="Every how many" ${rule ? "" : "hidden"}>
          <select name="rep_unit" aria-label="Repeat"><option value="">Never</option>${FREQS.map((x) => `<option value="${x}" ${rule && rule.freq === x ? "selected" : ""}></option>`).join("")}</select></div>
        <div class="seg multi" data-days ${rule && rule.freq === "WEEKLY" ? "" : "hidden"}>${BYDAY.map((x, i) =>
          `<button type="button" data-d="${i}" class="${rule && rule.byday.includes(i) ? "on" : ""}" aria-label="${WD[(i + 1) % 7]}">${"MTWTFSS"[i]}</button>`).join("")}</div>
      </div></div>
      <div class="field" data-ends ${rule ? "" : "hidden"}><span>Ends</span><input type="date" name="rep_until" value="${endsInit}" aria-label="Last day"></div>
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
    const quick = f.querySelector('[data-seg="quick"]');
    quick.addEventListener("change", () => { f.due.value = segValue(f, "quick"); });
    f.due.addEventListener("input", () => { for (const b of quick.children) b.classList.toggle("on", b.dataset.v === f.due.value); });
    const UNIT = { MINUTELY: "Minute", HOURLY: "Hour", DAILY: "Day", WEEKLY: "Week", MONTHLY: "Month", YEARLY: "Year" };
    const repUi = () => {
      const u = f.rep_unit.value, one = Number(f.rep_n.value) === 1;
      for (const o of f.rep_unit.options) if (o.value) o.textContent = UNIT[o.value] + (one ? "" : "s");
      f.rep_n.hidden = !u;
      f.querySelector("[data-days]").hidden = u !== "WEEKLY";
      f.querySelector("[data-ends]").hidden = !u;
    };
    repUi();
    f.rep_unit.addEventListener("change", repUi);
    f.rep_n.addEventListener("input", repUi);
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
        if (f.category.value !== cat0) {
          if (f.category.value) nd.category = f.category.value; else delete nd.category;
          delete nd.category_auto;
        }
        const u = f.rep_unit.value;
        if (!u) delete nd.repeat;
        else {
          const n = Math.min(100000, Math.max(1, Math.round(Number(f.rep_n.value) || 1)));
          const days = [...f.querySelectorAll("[data-days] .on")].map((b) => BYDAY[+b.dataset.d]).join(",");
          const same = rule && rule.freq === u;
          const ends = f.rep_until.value;
          nd.repeat = formatRule(same ? rule : { parts: {} }, {
            FREQ: u, INTERVAL: n > 1 ? n : null,
            BYDAY: u === "WEEKLY" ? days || null : same ? rule.parts.BYDAY ?? null : null,
            ...(ends === endsInit && rule ? { COUNT: rule.parts.COUNT ?? null, UNTIL: rule.parts.UNTIL ?? null }
              : { COUNT: null, UNTIL: ends ? ends.replace(/-/g, "") : null }),
          });
        }
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
      ui.list = "lists"; saveUi(); sheet.close(); render();
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

/* The address to show for a place. Older builds saved "Where you were at 08:23" instead of one. */
function placeAddr(d) {
  if (d.lat == null) return null;
  const a = d.address && !/^Where you were at /.test(d.address) ? d.address : null;
  return a || `${d.lat.toFixed(5)}, ${d.lon.toFixed(5)}`;
}
function hasAddr(d) { return !!d.address && !/^Where you were at /.test(d.address); }

function editPlace(id) {
  const p = id ? get("place", id) : null;
  const d = p ? { ...p.data } : { name: "", radius: 150 };
  const where = () => placeAddr(d) || "Not set";
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
    // Name the spot: the coordinates show at once, the street replaces them if the hub finds one.
    // The answer also goes into the saved place, in case Save beat it or the sheet was just looked at.
    let rseq = 0, savedId = id;
    const nameSpot = async () => {
      const mine = ++rseq, lat = d.lat, lon = d.lon;
      let r;
      try { r = await hub(`api/reverse?lat=${lat.toFixed(5)}&lon=${lon.toFixed(5)}`); } catch (e) { return; }
      if (!r.name) return;
      if (mine === rseq && d.lat === lat && d.lon === lon) { d.address = r.name; show(); }
      const rec = savedId && get("place", savedId);
      if (rec && rec.data.lat === lat && rec.data.lon === lon && !hasAddr(rec.data)) put("place", savedId, { ...rec.data, address: r.name });
    };
    if (d.lat != null && !hasAddr(d)) { delete d.address; nameSpot(); }
    f.querySelector('[data-act="here"]').onclick = (e) => {
      const b = e.currentTarget;
      b.textContent = "Finding you";
      if (!navigator.geolocation) { b.textContent = "Location unavailable"; return; }
      navigator.geolocation.getCurrentPosition((pos) => {
        d.lat = pos.coords.latitude; d.lon = pos.coords.longitude; delete d.address;
        b.textContent = "Use where I am"; show(); nameSpot();
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
            rseq++; d.lat = r.lat; d.lon = r.lon; d.address = r.name;
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
      savedId = id || uid();
      put("place", savedId, d);
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
    data: { type: "text", text, status: "new", created_at: now(), device: N ? "phone" : "web", ...(ui.tab === "tasks" && inList() ? { list: ui.list } : {}) },
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
  const go = t.closest("[data-view], [data-open]");
  if (go) {
    const id = go.dataset.view || go.dataset.open;
    if (id !== ui.list) { ui.list = id; ui.showDone = false; saveUi(); window.scrollTo(0, 0); render(); }
    return;
  }
  const check = t.closest("[data-check]");
  if (check) {
    const it = get("item", check.dataset.check);
    if (!it) return;
    const done = !it.data.done_at;
    const nd = { ...it.data, done_at: done ? now() : null };
    const next = done && roll(nd);
    check.classList.add("pop");
    setTimeout(() => put("item", it.uid, next || nd), done ? 280 : 0);
    if (next && (next.remind_at || next.due)) toast("Next " + (next.remind_at ? whenText(next.remind_at) : dueText(next.due)));
    else if (done) toast("Done");
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
  if (act && act.dataset.act === "new-list") return editList(null);
  if (act && act.dataset.act === "edit-list") return editList(ui.list);
});

/* The phone's back key: close the sheet, else step out of a list. Returns true if it did something. */
window.uplinkBack = () => {
  if (sheet.open) { sheet.close(); return true; }
  if (ui.tab === "tasks" && inList()) { ui.list = "lists"; ui.showDone = false; saveUi(); window.scrollTo(0, 0); render(); return true; }
  return false;
};

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
