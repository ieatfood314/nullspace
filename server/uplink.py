#!/usr/bin/env python3
"""Uplink hub: one to-do list, notes and captures for every device on your network.

Stdlib only, plus `vobject` for the Radicale bridge (Radicale itself depends on it).

Data model: every record is (kind, uid, data, updated_at, deleted, rev).
  kind      list | item | place | capture
  data      a JSON object, its fields depend on the kind (see README "Data")
  updated_at  ms; the newer write wins, whole record (last-writer-wins)
  deleted   tombstone flag
  rev       the hub's change counter; clients ask for everything above the rev they saw

Clients (the web page and the phone app) sync through POST /api/sync. The hub does the rest:
  * captures (voice notes, screenshots, typed lines, shares) are turned into tasks and notes
    by a text-only LLM call, with a plain parser as fallback;
  * tasks are mirrored both ways into Radicale's task lists (so tasks.org and any CalDAV
    client see them) and into Ignition's need/want lists.
"""

import argparse
import fcntl
import gzip
import hashlib
import json
import mimetypes
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
WEB_DIR = ROOT / "web"
KINDS = ("list", "item", "place", "capture")
MAX_BODY = 12 * 1024 * 1024  # screenshots are the big ones
def _local_tz():
    name = os.environ.get("UPLINK_TZ") or os.environ.get("TZ", "").lstrip(":")
    if not name:
        try:
            name = os.path.realpath("/etc/localtime").split("/zoneinfo/", 1)[1]
        except (IndexError, OSError):
            name = "UTC"
    try:
        return ZoneInfo(name)
    except Exception:
        return ZoneInfo("UTC")


TZ = _local_tz()


def now_ms():
    return int(time.time() * 1000)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def new_uid():
    return secrets.token_hex(8)


# ---------------------------------------------------------------- store


class Store:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS records (
                kind TEXT NOT NULL, uid TEXT NOT NULL, data TEXT NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                rev INTEGER NOT NULL, PRIMARY KEY (kind, uid));
            CREATE INDEX IF NOT EXISTS records_rev ON records(rev);
            CREATE TABLE IF NOT EXISTS ext (
                system TEXT NOT NULL, ext_id TEXT NOT NULL, uid TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT '{}', PRIMARY KEY (system, ext_id));
            CREATE INDEX IF NOT EXISTS ext_uid ON ext(system, uid);
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            """
        )
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)

    # meta
    def get(self, key, default=None):
        r = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return json.loads(r[0]) if r else default

    def set(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", (key, json.dumps(value)))

    def rev(self):
        return self.get("rev", 0)

    def _next_rev(self):
        r = self.rev() + 1
        self.set("rev", r)
        return r

    # records
    def one(self, kind, uid):
        r = self.db.execute("SELECT * FROM records WHERE kind=? AND uid=?", (kind, uid)).fetchone()
        return out(r) if r else None

    def all(self, kind, live=True):
        q = "SELECT * FROM records WHERE kind=?" + (" AND deleted=0" if live else "")
        return [out(r) for r in self.db.execute(q, (kind,))]

    def since(self, rev):
        return [out(r) for r in self.db.execute("SELECT * FROM records WHERE rev > ? ORDER BY rev", (rev,))]

    def merge(self, rec):
        """Last writer wins. Returns True when the record was taken."""
        kind, uid = rec.get("kind"), str(rec.get("uid") or "")[:64]
        if kind not in KINDS or not uid or not re.fullmatch(r"[A-Za-z0-9._@:-]+", uid):
            return False
        at = int(rec.get("updated_at") or 0)
        data = rec.get("data") if isinstance(rec.get("data"), dict) else {}
        with self.lock:
            cur = self.db.execute("SELECT updated_at FROM records WHERE kind=? AND uid=?", (kind, uid)).fetchone()
            if cur and cur[0] >= at:
                return False
            self.db.execute(
                "INSERT OR REPLACE INTO records (kind, uid, data, updated_at, deleted, rev) VALUES (?,?,?,?,?,?)",
                (kind, uid, json.dumps(data, separators=(",", ":")), at, 1 if rec.get("deleted") else 0, self._next_rev()),
            )
            self.changed.notify_all()
            return True

    def put(self, kind, uid, data, deleted=False, at=None):
        """A hub-side write: always newer than what's there."""
        with self.lock:
            cur = self.one(kind, uid)
            at = max(at or now_ms(), (cur["updated_at"] + 1) if cur else 0)
            self.merge({"kind": kind, "uid": uid, "data": data, "updated_at": at, "deleted": deleted})
            return at

    # ext links (radicale files, ignition ids)
    def ext(self, system, ext_id):
        r = self.db.execute("SELECT * FROM ext WHERE system=? AND ext_id=?", (system, ext_id)).fetchone()
        return {"uid": r["uid"], "state": json.loads(r["state"])} if r else None

    def ext_by_uid(self, system, uid):
        return [
            {"ext_id": r["ext_id"], "state": json.loads(r["state"])}
            for r in self.db.execute("SELECT * FROM ext WHERE system=? AND uid=?", (system, uid))
        ]

    def ext_all(self, system):
        return {
            r["ext_id"]: {"uid": r["uid"], "state": json.loads(r["state"])}
            for r in self.db.execute("SELECT * FROM ext WHERE system=?", (system,))
        }

    def ext_set(self, system, ext_id, uid, state):
        self.db.execute(
            "INSERT OR REPLACE INTO ext (system, ext_id, uid, state) VALUES (?,?,?,?)",
            (system, ext_id, uid, json.dumps(state)),
        )

    def ext_drop(self, system, ext_id):
        self.db.execute("DELETE FROM ext WHERE system=? AND ext_id=?", (system, ext_id))


def out(r):
    return {
        "kind": r["kind"], "uid": r["uid"], "data": json.loads(r["data"]),
        "updated_at": r["updated_at"], "deleted": bool(r["deleted"]), "rev": r["rev"],
    }


# ---------------------------------------------------------------- times


def local_now():
    return datetime.now(TZ)


def parse_local(s):
    """'YYYY-MM-DD' or 'YYYY-MM-DDTHH:MM' in local time -> aware datetime (dates at 09:00)."""
    if not s:
        return None
    try:
        if len(s) == 10:
            d = date.fromisoformat(s)
            return datetime(d.year, d.month, d.day, 9, 0, tzinfo=TZ)
        return datetime.fromisoformat(s[:16]).replace(tzinfo=TZ)
    except ValueError:
        return None


def clean_due(s):
    if not s or not isinstance(s, str):
        return None
    s = s.strip()[:16]
    if re.fullmatch(r"\d{4}-\d\d-\d\d", s):
        return s
    if re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d", s):
        return s
    return None


# ---------------------------------------------------------------- lists, places


class Model:
    """Helpers over the store that the bridges and the processor share."""

    def __init__(self, store):
        self.s = store

    def lists(self):
        return self.s.all("list")

    def list_by_name(self, name):
        if not name:
            return None
        key = norm(name)
        for l in self.lists():
            if norm(l["data"].get("name", "")) == key:
                return l
        # "need" / "want" / "need to do"
        for l in self.lists():
            if norm(l["data"].get("name", "")).startswith(key) and len(key) >= 4:
                return l
        return None

    def inbox(self):
        for l in self.lists():
            if l["data"].get("inbox"):
                return l["uid"]
        uid = "inbox"
        self.s.put("list", uid, {"name": "Inbox", "inbox": True, "sort": -1})
        return uid

    def places(self):
        return self.s.all("place")

    def place_by_name(self, name, create=True):
        if not name:
            return None
        key = norm(re.sub(r"^(the|my)\s+", "", name.strip(), flags=re.I))
        for p in self.places():
            if norm(p["data"].get("name", "")) == key:
                return p["uid"]
        if not create:
            return None
        uid = new_uid()
        self.s.put("place", uid, {"name": key.capitalize(), "radius": 150})
        return uid


def norm(s):
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", "", (s or "").lower())).strip()


# ---------------------------------------------------------------- the LLM step


PROMPT = """You file what someone captured on their phone into their to-do list and notes.
Reply with one JSON object only, no prose:
{"summary": "<one short line saying what this capture is>",
 "items": [{"type": "task" or "note", "title": "...", "notes": "...", "list": "<list name or null>",
            "due": "YYYY-MM-DD" or "YYYY-MM-DDTHH:MM" or null,
            "remind_at": "YYYY-MM-DDTHH:MM" or null,
            "place": "<place name or null>", "place_on": "arrive" or "leave" or null}]}

Rules:
- Voice notes and typed lines are what the person said. Fix obvious speech-to-text slips.
- "Remind me to X when I get home" is a task titled "X" with place "home", place_on "arrive".
  "when I leave work" is place "work", place_on "leave". Use place names the person uses (home, work,
  school, gym, a shop name); reuse a known place when it matches.
- "Remind me at 5" or "tomorrow morning" sets remind_at (morning 09:00, afternoon 14:00, evening 19:00,
  tonight 20:00). A deadline ("due Friday", "by the 20th") sets due. Times are local; the current time is below.
- Titles are short imperative phrases in the person's words, no trailing period. Put any detail in notes.
- Several separate to-dos in one capture become several tasks.
- Something to remember but not to do (an idea, a fact, a quote) is a note: title is a short label,
  notes holds the content.
- Screenshots: you get the text on screen and the app. Keep what is worth keeping: an event becomes a
  task with due, an order or ticket becomes a note with the useful numbers, an article becomes a note
  with its title and a two-line gist. Ignore buttons, menus and status-bar noise. If nothing on screen
  is worth keeping, make one note that says what the screen was.
- When a list name clearly fits (for example homework for a school task), use it; otherwise null.
- Never invent facts that aren't in the capture."""


class Brain:
    def __init__(self, model):
        self.m = model
        self.url = os.environ.get("UPLINK_LLM_URL", "https://openrouter.ai/api/v1/chat/completions")
        self.models = [
            m.strip()
            for m in os.environ.get(
                "UPLINK_LLM_MODELS",
                "nvidia/nemotron-3.5-super-vl:free,baidu/ernie-5.5-flash-preview:free,"
                "dots-studio/dots-3-note-preview:free,google/gemma-4-31b-it:free",
            ).split(",")
            if m.strip()
        ]
        self.key_file = os.environ.get("UPLINK_LLM_KEY_FILE", "")
        self.out_until = 0  # daily free quota used up until this time
        self.last_error = ""
        self.last_model = ""

    def key(self):
        k = os.environ.get("UPLINK_LLM_KEY", "")
        if not k and self.key_file:
            try:
                k = Path(self.key_file).expanduser().read_text().strip()
            except OSError:
                k = ""
        return k

    def ask(self, capture):
        key = self.key()
        if not key or time.time() < self.out_until:
            return None
        lists = ", ".join(l["data"].get("name", "") for l in self.m.lists()) or "none"
        places = ", ".join(p["data"].get("name", "") for p in self.m.places()) or "none"
        n = local_now()
        parts = [
            f"Now: {n.strftime('%A %Y-%m-%d %H:%M')} (local time)",
            f"Lists: {lists}",
            f"Known places: {places}",
            f"Capture type: {capture.get('type')}",
        ]
        if capture.get("app"):
            parts.append(f"App on screen: {capture['app']}")
        if capture.get("text"):
            parts.append("What they said or typed:\n" + capture["text"][:4000])
        if capture.get("screen_text"):
            parts.append("Text on screen:\n" + capture["screen_text"][:6000])
        msg = [{"role": "system", "content": PROMPT}, {"role": "user", "content": "\n".join(parts)}]
        for model in self.models:
            body = {"model": model, "messages": msg, "temperature": 0.2, "max_tokens": 1500}
            try:
                req = urllib.request.Request(
                    self.url, json.dumps(body).encode(),
                    {"Authorization": f"Bearer {key}", "Content-Type": "application/json",
                     "X-Title": "Uplink"},
                )
                with urllib.request.urlopen(req, timeout=40) as r:
                    res = json.load(r)
                text = res["choices"][0]["message"]["content"] or ""
                parsed = first_json(text)
                if parsed is not None and isinstance(parsed.get("items"), list):
                    self.last_model, self.last_error = model, ""
                    return parsed
                self.last_error = f"{model}: no JSON"
            except urllib.error.HTTPError as e:
                detail = e.read()[:400].decode("utf-8", "replace")
                self.last_error = f"{model}: HTTP {e.code} {detail[:160]}"
                if e.code == 429 and "free-models-per-day" in detail.replace("_", "-").lower():
                    self.out_until = next_utc_midnight()
                    return None
                if e.code in (401, 402):
                    return None
            except Exception as e:  # network, timeout, bad JSON
                self.last_error = f"{model}: {e}"
        log("llm failed:", self.last_error)
        return None


def next_utc_midnight():
    t = datetime.now(timezone.utc)
    return (t.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)).timestamp()


def first_json(text):
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    start = text.find("{")
    while start >= 0:
        depth = 0
        for i in range(start, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:i + 1])
                    except ValueError:
                        break
        start = text.find("{", start + 1)
    return None


WORDNUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
           "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "an": 1, "a": 1}
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def basic_parse(capture):
    """What we can do without the LLM: one task or note, with place and time phrases pulled out."""
    text = (capture.get("text") or "").strip()
    if capture.get("type") == "screenshot" and not text:
        screen = (capture.get("screen_text") or "").strip()
        title = (capture.get("app") or "Screenshot").split(".")[-1].capitalize()
        return {"summary": f"Screenshot of {title}",
                "items": [{"type": "note", "title": f"Screenshot: {title}", "notes": screen[:2000]}]}
    if not text:
        return {"summary": "Empty", "items": []}
    t = text.rstrip(".!")
    item = {"type": "note", "title": t, "notes": ""}
    m = re.match(r"^(?:please\s+)?(?:remind me (?:to |that |about )?|todo:?\s*|to do:?\s*|i need to |i have to |don't forget to )(.+)$", t, re.I)
    if m:
        item["type"] = "task"
        t = m.group(1)
    elif re.match(r"^(buy|call|email|text|get|pick up|book|pay|send|finish|fix|clean|study|write|read|order|return|check|ask|tell|make|take)\b", t, re.I):
        item["type"] = "task"
    pm = re.search(r"\b(?:when|once|as soon as) i (get|arrive|am|get back|leave|head out of|go to|get to)\s+(?:to |at |from |back )?((?:the |my )?[\w' ]{2,30}?)$", t, re.I)
    if pm:
        verb = pm.group(1).lower()
        item["place"] = re.sub(r"^(the|my) ", "", pm.group(2).strip(), flags=re.I)
        item["place_on"] = "leave" if verb in ("leave", "head out of") else "arrive"
        item["type"] = "task"
        t = t[:pm.start()].strip(" ,")
    when, t = pull_time(t)
    if when:
        item["remind_at"] = when
        item["type"] = "task"
    item["title"] = (t[:1].upper() + t[1:]) if t else text
    if item["type"] == "note" and len(item["title"]) > 80:
        item["notes"] = item["title"]
        item["title"] = item["title"][:60].rsplit(" ", 1)[0] + "…"
    return {"summary": item["title"], "items": [item]}


def pull_time(t):
    n = local_now()
    day = None
    hour = None
    m = re.search(r"\bin (\d+|an?|one|two|three|four|five|ten|twenty|thirty) (minutes?|mins?|hours?)\b", t, re.I)
    if m:
        k = m.group(1).lower()
        amount = int(k) if k.isdigit() else WORDNUM.get(k, {"twenty": 20, "thirty": 30}.get(k, 1))
        delta = timedelta(hours=amount) if m.group(2).lower().startswith("h") else timedelta(minutes=amount)
        return (n + delta).strftime("%Y-%m-%dT%H:%M"), (t[:m.start()] + t[m.end():]).strip(" ,")
    rest = t
    m = re.search(r"\b(today|tonight|tomorrow|on (?:" + "|".join(DAYS) + r")|(?:" + "|".join(DAYS) + r"))\b", rest, re.I)
    if m:
        w = m.group(1).lower().replace("on ", "")
        if w in ("today", "tonight"):
            day = n.date()
            if w == "tonight":
                hour = (20, 0)
        elif w == "tomorrow":
            day = n.date() + timedelta(days=1)
        else:
            ahead = (DAYS.index(w) - n.weekday()) % 7 or 7
            day = n.date() + timedelta(days=ahead)
        rest = (rest[:m.start()] + rest[m.end():]).strip(" ,")
    m = re.search(r"\bat (\d{1,2})(?::(\d\d))?\s*(am|pm|a\.m\.|p\.m\.)?\b", rest, re.I)
    if m:
        h, mi = int(m.group(1)), int(m.group(2) or 0)
        ap = (m.group(3) or "").lower().replace(".", "")
        if ap == "pm" and h < 12:
            h += 12
        elif ap == "am" and h == 12:
            h = 0
        elif not ap and h < 7:
            h += 12  # "at 5" means 5 pm
        if h < 24:
            hour = (h, mi)
            rest = (rest[:m.start()] + rest[m.end():]).strip(" ,")
    for word, hh in (("morning", 9), ("afternoon", 14), ("evening", 19)):
        mm = re.search(rf"\b(?:in the |this )?{word}\b", rest, re.I)
        if mm and hour is None:
            hour = (hh, 0)
            rest = (rest[:mm.start()] + rest[mm.end():]).strip(" ,")
    if day is None and hour is None:
        return None, t
    day = day or n.date()
    hour = hour or (9, 0)
    when = datetime(day.year, day.month, day.day, hour[0], hour[1], tzinfo=TZ)
    if when < n and day == n.date():
        when += timedelta(days=1)
    return when.strftime("%Y-%m-%dT%H:%M"), rest


class Processor:
    """Turns new captures into items, one at a time, on its own thread."""

    def __init__(self, store, model, brain):
        self.s, self.m, self.brain = store, model, brain
        self.wake = threading.Event()
        self.busy = set()
        self.done = threading.Condition()

    def pending(self):
        return [c for c in self.s.all("capture") if c["data"].get("status", "new") == "new"]

    def run(self):
        while True:
            self.wake.wait(60)
            self.wake.clear()
            for c in self.pending():
                try:
                    self.process(c)
                except Exception:
                    traceback.print_exc()
                with self.done:
                    self.done.notify_all()

    def wait_for(self, uids, timeout):
        end = time.time() + timeout
        with self.done:
            while time.time() < end:
                left = [u for u in uids if (self.s.one("capture", u) or {}).get("data", {}).get("status", "new") == "new"]
                if not left:
                    return
                self.done.wait(min(1, end - time.time()))

    def process(self, cap, force=False):
        d = dict(cap["data"])
        result = self.brain.ask(d)
        basic = result is None
        if basic:
            result = basic_parse(d)
        made = []
        for it in (result.get("items") or [])[:12]:
            made.append(self.make_item(it, cap["uid"], d))
        d.update(status="done", summary=str(result.get("summary") or "")[:200], items=made,
                 processed_at=int(time.time()), basic=basic)
        if force:
            # Reprocessing replaces the items the last pass made.
            for old in cap["data"].get("items") or []:
                rec = self.s.one("item", old)
                if rec and not rec["deleted"]:
                    self.s.put("item", old, rec["data"], deleted=True)
        self.s.put("capture", cap["uid"], d)
        log(f"capture {cap['uid']} ({d.get('type')}): {len(made)} item(s){' (basic)' if basic else ''}")

    def make_item(self, it, cap_uid, cap):
        kind = "task" if it.get("type") == "task" else "note"
        title = str(it.get("title") or "").strip().rstrip(".")[:300] or "Untitled"
        title = title[:1].upper() + title[1:]
        data = {
            "type": kind, "title": title, "notes": str(it.get("notes") or "")[:20000],
            "created_at": int(time.time()), "source": cap.get("type", "text"), "capture": cap_uid,
        }
        lst = self.m.list_by_name(it.get("list")) if kind == "task" else None
        data["list"] = lst["uid"] if lst else (self.m.inbox() if kind == "task" else None)
        due = clean_due(it.get("due"))
        if due:
            data["due"] = due
        rem = parse_local(clean_due(it.get("remind_at")))
        if rem:
            data["remind_at"] = int(rem.timestamp())
        if it.get("place"):
            data["place"] = self.m.place_by_name(str(it["place"])[:40])
            data["place_on"] = "leave" if it.get("place_on") == "leave" else "arrive"
            data["type"] = "task"
            if not data.get("list"):
                data["list"] = self.m.inbox()
        uid = new_uid()
        self.s.put("item", uid, data)
        return uid


# ---------------------------------------------------------------- Radicale bridge


class Radicale:
    """Mirrors tasks into Radicale's storage folder, both ways.

    Radicale's multifilesystem storage is a folder per collection with an .ics per item; other
    programs may edit it as long as they hold the flock on .Radicale.lock, which is what we do.
    Every task list becomes a VTODO collection. Notes stay in Uplink."""

    SYSTEM = "radicale"

    def __init__(self, store, model, user_dir, lock_file):
        self.s, self.m = store, model
        self.dir = Path(user_dir)
        self.lock_file = Path(lock_file)
        self.last_run = 0
        self.last_error = ""

    def collections(self):
        out = {}
        for d in sorted(self.dir.iterdir()):
            props = d / ".Radicale.props"
            if not d.is_dir() or not props.exists():
                continue
            try:
                p = json.loads(props.read_text())
            except ValueError:
                continue
            if p.get("tag") != "VCALENDAR" or "VTODO" not in p.get("C:supported-calendar-component-set", "VTODO"):
                continue
            out[d.name] = p
        return out

    def run_once(self):
        import vobject  # noqa: F401  (fail early and loudly if missing)

        with open(self.lock_file, "a+") as lf:
            fcntl.flock(lf, fcntl.LOCK_EX)
            try:
                with self.s.lock:
                    self.sync_lists()
                    self.pull()
                    self.push()
            finally:
                fcntl.flock(lf, fcntl.LOCK_UN)
        self.last_run = time.time()

    # lists <-> collections
    def sync_lists(self):
        cols = self.collections()
        by_col = {l["data"].get("radicale"): l for l in self.s.all("list", live=False) if l["data"].get("radicale")}
        for col, p in cols.items():
            name = p.get("D:displayname") or col
            mtime = int((self.dir / col / ".Radicale.props").stat().st_mtime * 1000)
            l = by_col.get(col)
            if l is None:
                existing = self.m.list_by_name(name)
                if existing and not existing["data"].get("radicale"):
                    d = dict(existing["data"], radicale=col)
                    self.s.put("list", existing["uid"], d)
                    continue
                d = {"name": name, "radicale": col, "color": p.get("ICAL:calendar-color")}
                key = norm(name)
                if key in ("need to do", "need"):
                    d["ignition"] = "need"
                if key in ("want to do", "want"):
                    d["ignition"] = "want"
                self.s.put("list", col, d, at=mtime)
            elif l["deleted"]:
                continue  # deleted here: removed from Radicale in push()
            elif l["data"].get("name") != name and mtime > l["updated_at"]:
                self.s.put("list", l["uid"], dict(l["data"], name=name), at=mtime)
        for l in self.s.all("list", live=False):
            col = l["data"].get("radicale")
            if l["deleted"]:
                if col and (self.dir / col).exists() and not any((self.dir / col).glob("*.ics")):
                    self._remove_collection(col)
                continue
            if not col:
                col = l["uid"]
                self._write_props(col, l["data"].get("name", "List"))
                self.s.put("list", l["uid"], dict(l["data"], radicale=col))
            elif col not in cols:
                if not (self.dir / col).exists():
                    # Removed in a CalDAV client: drop the list (its tasks get tombstoned in pull()).
                    self.s.put("list", l["uid"], l["data"], deleted=True)
            else:
                name = cols[col].get("D:displayname")
                if name != l["data"].get("name"):
                    self._write_props(col, l["data"].get("name", "List"), cols[col])

    def _write_props(self, col, name, old=None):
        d = self.dir / col
        d.mkdir(exist_ok=True)
        p = dict(old or {"C:supported-calendar-component-set": "VTODO", "tag": "VCALENDAR"})
        p["D:displayname"] = name
        tmp = d / ".Radicale.props.uplink"
        tmp.write_text(json.dumps(p))
        tmp.replace(d / ".Radicale.props")

    def _remove_collection(self, col):
        d = self.dir / col
        for f in d.iterdir():
            if f.is_file():
                f.unlink()
        cache = d / ".Radicale.cache"
        if cache.exists():
            import shutil
            shutil.rmtree(cache)
        try:
            d.rmdir()
        except OSError:
            pass

    def col_to_list(self):
        return {l["data"].get("radicale"): l["uid"] for l in self.s.all("list") if l["data"].get("radicale")}

    # radicale -> hub
    def pull(self):
        cols = self.col_to_list()
        links = self.s.ext_all(self.SYSTEM)
        seen = set()
        for col, list_uid in cols.items():
            d = self.dir / col
            if not d.is_dir():
                continue
            for f in d.glob("*.ics"):
                ext_id = f"{col}/{f.name}"
                seen.add(ext_id)
                raw = f.read_bytes()
                h = hashlib.sha1(raw).hexdigest()
                link = links.get(ext_id)
                if link and link["state"].get("hash") == h:
                    continue
                todo = parse_vtodo(raw)
                if todo is None:
                    continue
                uid = safe_uid(todo["uid"])
                mtime = int(f.stat().st_mtime * 1000)
                fields = todo["fields"]
                fields["list"] = list_uid
                cur = self.s.one("item", uid)
                if cur is None:
                    data = {"type": "task", "source": "radicale", **fields}
                    data.setdefault("created_at", int(f.stat().st_mtime))
                    self.s.put("item", uid, data, at=mtime)
                elif mtime > cur["updated_at"] or cur["deleted"]:
                    if cur["deleted"] and mtime <= cur["updated_at"]:
                        pass  # deleted here after that edit; push() removes the file
                    else:
                        self.s.put("item", uid, {**cur["data"], **fields}, at=mtime)
                # Moving between lists in tasks.org writes the new file before deleting the old one.
                for other in self.s.ext_by_uid(self.SYSTEM, uid):
                    if other["ext_id"] != ext_id and not (self.dir / other["ext_id"]).exists():
                        self.s.ext_drop(self.SYSTEM, other["ext_id"])
                item = self.s.one("item", uid)
                self.s.ext_set(self.SYSTEM, ext_id, uid, {"hash": h, "sig": sig(item["data"]) if item else ""})
        for ext_id, link in links.items():
            if ext_id in seen or (self.dir / ext_id).exists():
                continue
            self.s.ext_drop(self.SYSTEM, ext_id)
            if self.s.ext_by_uid(self.SYSTEM, link["uid"]):
                continue  # it moved
            cur = self.s.one("item", link["uid"])
            if cur and not cur["deleted"] and cur["data"].get("type") == "task":
                # Deleted in tasks.org (or its list was removed).
                self.s.put("item", link["uid"], cur["data"], deleted=True)

    # hub -> radicale
    def push(self):
        cols = {v: k for k, v in self.col_to_list().items()}  # list uid -> collection
        for item in self.s.all("item", live=False):
            d = item["data"]
            links = self.s.ext_by_uid(self.SYSTEM, item["uid"])
            want_col = cols.get(d.get("list")) if (d.get("type") == "task" and not item["deleted"]) else None
            for link in links:
                col = link["ext_id"].split("/")[0]
                if col != want_col:
                    p = self.dir / link["ext_id"]
                    if p.exists():
                        p.unlink()
                    self.s.ext_drop(self.SYSTEM, link["ext_id"])
            if not want_col:
                continue
            fname = f"{item['uid']}.ics"
            cur = [l for l in links if l["ext_id"].split("/")[0] == want_col]
            if cur:
                fname = cur[0]["ext_id"].split("/", 1)[1]
                if cur[0]["state"].get("sig") == sig(d):
                    continue
            path = self.dir / want_col / fname
            old = path.read_bytes() if path.exists() else None
            raw = build_vtodo(item["uid"], d, old)
            tmp = path.with_suffix(".uplink-tmp")
            tmp.write_bytes(raw)
            tmp.replace(path)
            self.s.ext_set(self.SYSTEM, f"{want_col}/{fname}", item["uid"],
                           {"hash": hashlib.sha1(raw).hexdigest(), "sig": sig(d)})


def safe_uid(u):
    u = re.sub(r"[^A-Za-z0-9._@:-]", "-", u or "")[:64]
    return u or new_uid()


def sig(d):
    keys = ("title", "notes", "done_at", "due", "priority", "list", "type")
    return hashlib.sha1(json.dumps([d.get(k) for k in keys]).encode()).hexdigest()


ICAL_PRI = {3: 1, 2: 5, 1: 9, 0: 0}


def parse_vtodo(raw):
    import vobject

    try:
        cal = vobject.readOne(raw.decode("utf-8", "replace"))
        todo = cal.vtodo
    except Exception:
        return None
    f = {}

    def val(name):
        c = todo.contents.get(name)
        return c[0].value if c else None

    f["title"] = str(val("summary") or "").strip() or "Untitled"
    f["notes"] = str(val("description") or "")
    status = str(val("status") or "").upper()
    comp = val("completed")
    if status == "COMPLETED" or comp:
        f["done_at"] = int(to_dt(comp).timestamp()) if comp else int(time.time())
    else:
        f["done_at"] = None
    due = val("due")
    if isinstance(due, datetime):
        f["due"] = to_dt(due).astimezone(TZ).strftime("%Y-%m-%dT%H:%M")
    elif isinstance(due, date):
        f["due"] = due.isoformat()
    else:
        f["due"] = None
    try:
        p = int(val("priority") or 0)
    except ValueError:
        p = 0
    f["priority"] = 0 if p == 0 else 3 if p <= 4 else 2 if p == 5 else 1
    created = val("created")
    if isinstance(created, datetime):
        f["created_at"] = int(to_dt(created).timestamp())
    return {"uid": str(val("uid") or ""), "fields": f}


def to_dt(v):
    if isinstance(v, datetime):
        return v if v.tzinfo else v.replace(tzinfo=TZ)
    return datetime(v.year, v.month, v.day, tzinfo=TZ)


def build_vtodo(uid, d, old=None):
    import vobject

    cal = None
    if old:
        try:
            cal = vobject.readOne(old.decode("utf-8", "replace"))
            todo = cal.vtodo
        except Exception:
            cal = None
    if cal is None:
        cal = vobject.iCalendar()
        cal.add("prodid").value = "-//Uplink//EN"
        todo = cal.add("vtodo")
        todo.add("uid").value = uid

    def set_(name, value):
        for c in list(todo.contents.get(name, [])):
            todo.remove(c)
        if value is not None and value != "":
            todo.add(name).value = value

    utc = vobject.icalendar.utc  # vobject only serializes its own UTC object
    stamp = datetime.now(utc).replace(microsecond=0)
    set_("summary", d.get("title") or "Untitled")
    set_("description", d.get("notes") or None)
    set_("dtstamp", stamp)
    set_("last-modified", stamp)
    if "created" not in todo.contents and d.get("created_at"):
        todo.add("created").value = datetime.fromtimestamp(d["created_at"], utc).replace(microsecond=0)
    if d.get("done_at"):
        set_("status", "COMPLETED")
        set_("completed", datetime.fromtimestamp(d["done_at"], utc).replace(microsecond=0))
        set_("percent-complete", "100")
    else:
        set_("status", "NEEDS-ACTION")
        set_("completed", None)
        set_("percent-complete", None)
    due = d.get("due")
    set_("due", None)
    if due and len(due) == 10:
        todo.add("due").value = date.fromisoformat(due)
    elif due:
        dt = parse_local(due)
        if dt:
            todo.add("due").value = dt.astimezone(utc)
    pri = ICAL_PRI.get(int(d.get("priority") or 0), 0)
    set_("priority", str(pri) if pri else None)
    return cal.serialize().encode()


# ---------------------------------------------------------------- Ignition bridge


class Ignition:
    """Keeps Ignition's need/want lists in step with the lists marked need/want here,
    through Ignition's own POST /api/sync (the one its phone app uses)."""

    SYSTEM = "ignition"

    def __init__(self, store, model, url):
        self.s, self.m, self.url = store, model, url.rstrip("/")
        self.last_run = 0
        self.last_error = ""

    def call(self, body):
        req = urllib.request.Request(self.url + "/api/sync", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)

    def run_once(self):
        with self.s.lock:
            ign_lists = {l["uid"]: l["data"]["ignition"] for l in self.s.all("list") if l["data"].get("ignition")}
            by_bucket = {v: k for k, v in ign_lists.items()}
            pushed = self.s.get("ignition_pushed", 0)
            outgoing = []
            top = self.s.rev()
            for rec in ([] if self.s.get("ignition_since", 0) == 0 else self.s.since(pushed)):
                if rec["kind"] != "item":
                    continue
                d = rec["data"]
                links = self.s.ext_by_uid(self.SYSTEM, rec["uid"])
                ign_id = links[0]["ext_id"] if links else (rec["uid"] if len(rec["uid"]) <= 40 else None)
                bucket = ign_lists.get(d.get("list")) if d.get("type") == "task" else None
                if rec["deleted"] or not bucket:
                    if links:
                        outgoing.append({"id": ign_id, "deleted": True, "updated_at": rec["updated_at"]})
                        self.s.ext_drop(self.SYSTEM, ign_id)
                    continue
                if not ign_id:
                    continue
                if not links and d.get("done_at"):
                    continue  # already done before Ignition saw it: keep it out of Ignition's history
                outgoing.append({
                    "id": ign_id, "list": bucket, "title": d.get("title") or "Untitled",
                    "notes": d.get("notes") or "", "due": (d.get("due") or "")[:10] or None,
                    "priority": int(d.get("priority") or 0), "estimate_min": d.get("estimate_min"),
                    "created_at": d.get("created_at") or int(time.time()), "done_at": d.get("done_at"),
                    "updated_at": rec["updated_at"], "deleted": False,
                })
                self.s.ext_set(self.SYSTEM, ign_id, rec["uid"], {})
            since = self.s.get("ignition_since", 0)
            if since == 0:
                outgoing = []  # pull and match first, push on the next pass
        res = self.call({"since": since, "items": outgoing})
        with self.s.lock:
            first = since == 0
            open_by_title = {}
            if first:
                for it in self.s.all("item"):
                    if it["data"].get("type") == "task" and not it["data"].get("done_at") and it["data"].get("list") in ign_lists:
                        open_by_title.setdefault(norm(it["data"].get("title")), it["uid"])
            for it in res.get("items") or []:
                ign_id = it["id"]
                link = self.s.ext(self.SYSTEM, ign_id)
                uid = link["uid"] if link else None
                if uid is None and not it.get("deleted"):
                    if self.s.one("item", ign_id):
                        uid = ign_id
                    elif first and not it.get("done_at"):
                        # First contact: the same task may already be in the Radicale list.
                        uid = open_by_title.pop(norm(it.get("title")), None)
                    uid = uid or ign_id
                if uid is None:
                    continue
                cur = self.s.one("item", uid)
                at = int(it.get("updated_at") or 0)
                if cur and cur["updated_at"] >= at:
                    self.s.ext_set(self.SYSTEM, ign_id, uid, {})
                    continue
                if it.get("deleted"):
                    if cur and not cur["deleted"] and cur["data"].get("list") in ign_lists:
                        self.s.put("item", uid, cur["data"], deleted=True, at=at)
                    self.s.ext_drop(self.SYSTEM, ign_id)
                    continue
                base = dict(cur["data"]) if cur and not cur["deleted"] else {"type": "task", "source": "ignition"}
                base.update(
                    title=it.get("title") or "Untitled", notes=it.get("notes") or "",
                    priority=int(it.get("priority") or 0), estimate_min=it.get("estimate_min"),
                    done_at=it.get("done_at"), created_at=it.get("created_at") or base.get("created_at"),
                    list=by_bucket.get(it.get("list")) or base.get("list"),
                )
                due = it.get("due")
                old_due = base.get("due") or ""
                # Ignition only keeps the date: keep our time of day if the date didn't change.
                base["due"] = old_due if (due and old_due[:10] == due) else (due or None)
                self.s.put("item", uid, base, at=at)
                self.s.ext_set(self.SYSTEM, ign_id, uid, {})
            self.s.set("ignition_since", int(res.get("rev") or since))
            # Our own writes above bumped rev; they came from Ignition, so don't echo them.
            self.s.set("ignition_pushed", 0 if first else top)
        self.last_run = time.time()


# ---------------------------------------------------------------- HTTP


class ApiError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code, self.msg = code, msg


def make_handler(app):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "uplink"

        def log_message(self, *a):
            pass

        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > MAX_BODY:
                raise ApiError(413, "too big")
            raw = self.rfile.read(n) if n else b""
            if self.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            return raw

        def json_body(self):
            try:
                return json.loads(self.body() or b"{}")
            except ValueError:
                raise ApiError(400, "bad json")

        def send(self, code, payload, ctype="application/json", cache=None):
            raw = payload if isinstance(payload, bytes) else json.dumps(payload, separators=(",", ":")).encode()
            gz = "gzip" in (self.headers.get("Accept-Encoding") or "") and len(raw) > 600 and (
                ctype.startswith(("application/json", "text/", "application/javascript", "image/svg")))
            if gz:
                raw = gzip.compress(raw, 6)
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(raw)))
            if gz:
                self.send_header("Content-Encoding", "gzip")
            self.send_header("Cache-Control", cache or "no-store")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(raw)

        def do_GET(self):
            self.route("GET")

        def do_HEAD(self):
            self.route("GET")

        def do_POST(self):
            self.route("POST")

        def do_PUT(self):
            self.route("PUT")

        def route(self, m):
            try:
                u = urllib.parse.urlparse(self.path)
                p = u.path
                q = urllib.parse.parse_qs(u.query)
                if p == "/api/sync" and m == "POST":
                    return self.send(200, app.sync(self.json_body()))
                if p == "/api/status":
                    return self.send(200, app.status())
                if p == "/api/geocode":
                    return self.send(200, app.geocode((q.get("q") or [""])[0]))
                if p.startswith("/api/shot/") and m in ("POST", "PUT"):
                    return self.send(200, app.save_shot(p.rsplit("/", 1)[1], self.body()))
                if p.startswith("/api/reprocess/") and m == "POST":
                    return self.send(200, app.reprocess(p.rsplit("/", 1)[1]))
                if p.startswith("/shots/"):
                    f = app.shot_path(p.rsplit("/", 1)[1].removesuffix(".webp").removesuffix(".jpg"))
                    if f and f.exists():
                        return self.send(200, f.read_bytes(), "image/" + f.suffix[1:], "max-age=31536000, immutable")
                    raise ApiError(404, "no such screenshot")
                if p.startswith("/fonts/"):
                    f = app.font(p.rsplit("/", 1)[1])
                    if f:
                        return self.send(200, f.read_bytes(), "font/otf", "max-age=2592000")
                if m == "GET":
                    return self.static(p)
                raise ApiError(404, "not found")
            except ApiError as e:
                self.send(e.code, {"error": e.msg})
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as e:
                traceback.print_exc()
                self.send(500, {"error": str(e)})

        def static(self, p):
            name = "index.html" if p in ("/", "") else p.lstrip("/")
            f = (WEB_DIR / name).resolve()
            if WEB_DIR.resolve() not in f.parents or not f.is_file():
                f = WEB_DIR / "index.html"
            if name.startswith("fonts/") and f.name == "index.html":
                raise ApiError(404, "no such font")
            ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
            if f.suffix == ".js":
                ctype = "application/javascript"
            if ctype.startswith("text/") or ctype == "application/javascript":
                ctype += "; charset=utf-8"
            self.send(200, f.read_bytes(), ctype, "no-cache")

    return H


class App:
    def __init__(self, args):
        self.s = Store(args.db)
        self.m = Model(self.s)
        self.brain = Brain(self.m)
        self.proc = Processor(self.s, self.m, self.brain)
        self.shots = Path(args.data) / "shots"
        self.shots.mkdir(parents=True, exist_ok=True)
        self.fonts = Path(os.environ.get("UPLINK_FONTS", "")) if os.environ.get("UPLINK_FONTS") else None
        rad = os.environ.get("UPLINK_RADICALE_DIR")
        self.radicale = None
        if rad and Path(rad).is_dir():
            lock = os.environ.get("UPLINK_RADICALE_LOCK") or str(Path(rad).parent.parent / ".Radicale.lock")
            self.radicale = Radicale(self.s, self.m, rad, lock)
        ign = os.environ.get("UPLINK_IGNITION_URL")
        self.ignition = Ignition(self.s, self.m, ign) if ign else None
        self.kick = threading.Event()
        self.devices = self.s.get("devices", {})

    # sync
    def sync(self, body):
        try:
            since = int(body.get("since") or 0)
        except (TypeError, ValueError):
            raise ApiError(400, "bad since")
        new_caps = []
        took = 0
        for rec in body.get("records") or []:
            fresh = rec.get("kind") == "capture" and self.s.one("capture", str(rec.get("uid"))) is None
            if self.s.merge(rec):
                took += 1
                if fresh and not rec.get("deleted"):
                    new_caps.append(rec["uid"])
        dev = str(body.get("device") or "")[:40]
        if dev:
            self.devices[dev] = {"seen": int(time.time()), **({"usage": body["usage"]} if isinstance(body.get("usage"), dict) else {})}
            self.s.set("devices", self.devices)
        if new_caps:
            self.proc.wake.set()
        if took:
            self.kick.set()
        if new_caps and body.get("wait"):
            self.proc.wait_for(new_caps, min(float(body.get("wait") or 0), 30))
        return {"rev": self.s.rev(), "now": now_ms(), "records": self.s.since(since)}

    def status(self):
        counts = {}
        for k in KINDS:
            counts[k] = len(self.s.all(k))
        b = self.brain
        return {
            "rev": self.s.rev(), "counts": counts, "devices": self.devices,
            "llm": {"key": bool(b.key()), "out_until": int(b.out_until), "last_error": b.last_error,
                    "last_model": b.last_model},
            "radicale": self.radicale and {"last_run": int(self.radicale.last_run), "error": self.radicale.last_error},
            "ignition": self.ignition and {"last_run": int(self.ignition.last_run), "error": self.ignition.last_error},
            "pending": len(self.proc.pending()),
        }

    def reprocess(self, uid):
        cap = self.s.one("capture", uid)
        if not cap:
            raise ApiError(404, "no such capture")
        self.proc.process(cap, force=True)
        self.kick.set()
        return {"ok": True, "capture": self.s.one("capture", uid)}

    def shot_path(self, uid):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", uid):
            return None
        for ext in ("webp", "jpg"):
            f = self.shots / f"{uid}.{ext}"
            if f.exists():
                return f
        return self.shots / f"{uid}.webp"

    def save_shot(self, uid, raw):
        uid = uid.split(".")[0]
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", uid) or not raw:
            raise ApiError(400, "bad screenshot")
        ext = "jpg" if raw[:3] == b"\xff\xd8\xff" else "webp"
        (self.shots / f"{uid}.{ext}").write_bytes(raw)
        cap = self.s.one("capture", uid)
        if cap and not cap["data"].get("image_on_hub"):
            self.s.put("capture", uid, {**cap["data"], "image_on_hub": True})
        return {"ok": True}

    def font(self, name):
        if not self.fonts:
            return None
        allowed = {"ndot55.otf": "Ndot55-Regular.otf", "ndot57.otf": "Ndot57-Regular.otf"}
        f = self.fonts / allowed.get(name, "-")
        return f if f.is_file() else None

    def geocode(self, q):
        q = q.strip()[:200]
        if not q:
            return []
        url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode(
            {"q": q, "format": "jsonv2", "limit": 5})
        req = urllib.request.Request(url, headers={"User-Agent": "uplink-hub/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                res = json.load(r)
        except Exception as e:
            raise ApiError(502, f"address lookup failed: {e}")
        return [{"name": x.get("display_name"), "lat": float(x["lat"]), "lon": float(x["lon"])} for x in res]

    # background
    def bridges(self):
        while True:
            self.kick.wait(30)
            self.kick.clear()
            time.sleep(0.5)  # let a burst of writes land
            for b in (self.radicale, self.ignition):
                if not b:
                    continue
                try:
                    b.run_once()
                    b.last_error = ""
                except Exception as e:
                    b.last_error = f"{type(e).__name__}: {e}"
                    log(type(b).__name__, "failed:", b.last_error)

    def start(self):
        self.m.inbox()
        threading.Thread(target=self.proc.run, daemon=True, name="processor").start()
        threading.Thread(target=self.bridges, daemon=True, name="bridges").start()
        self.proc.wake.set()
        self.kick.set()


def main():
    ap = argparse.ArgumentParser(description="Uplink hub")
    ap.add_argument("--host", default=os.environ.get("UPLINK_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("UPLINK_PORT", "4645")))
    data = os.environ.get("UPLINK_DATA", str(Path.home() / ".local/share/uplink"))
    ap.add_argument("--data", default=data)
    ap.add_argument("--db", default=None)
    args = ap.parse_args()
    args.db = args.db or str(Path(args.data) / "hub.db")
    app = App(args)
    app.start()
    srv = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    srv.daemon_threads = True
    log(f"uplink hub on {args.host}:{args.port}, data in {args.data}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
