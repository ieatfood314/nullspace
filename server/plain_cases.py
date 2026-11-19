#!/usr/bin/env python3
"""Writes the cases the phone's plain filer (android/.../Plain.kt) is checked against.

Plain.kt is a port of basic_parse, settle and the helpers they use. This runs the Python
versions over a set of phrases at a few pinned times and writes what they give to
android/app/src/test/resources/plain-cases.json, which PlainTest replays. Run it after changing
any of those functions, then port the change and run the phone's unit tests.
"""

import copy
import json
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nullspace as hub  # noqa: E402

ZONE = "America/Vancouver"
NOWS = ["2026-11-17T08:12:30", "2026-11-20T19:40:05", "2026-11-22T23:30:00", "2026-01-31T13:05:00"]

PHRASES = [
    "buy milk", "call the dentist tomorrow at 3", "remind me to call mom at 5", "remind me tomorrow to email Mr. Smith",
    "when I get home, take the bins out", "remind me to buy stamps when I'm at the post office",
    "when I leave work call Sarah", "pick up the parcel when I get to the library",
    "every Tuesday and Thursday at 4 practice piano", "water the plants every 2 weeks",
    "take pills every day for the next week", "stretch every 11.46 hours", "pay rent on the 1st of every month",
    "take out recycling every other Monday", "call grandma every weekday at 6pm", "journal every night",
    "pay rent monthly", "read the weekly report", "essay due Friday", "the essay is due by the 20th",
    "remind me Wednesday that the homework is due Friday", "dentist appointment December 3rd at 2:30pm",
    "remind me in 20 minutes to check the oven", "in an hour and a half call the bank", "remind me in half an hour",
    "remind me at 140 to leave", "meeting at 2.45 tomorrow", "submit the form by 5pm",
    "remind me 10 minutes before my 3 pm meeting to grab my notes", "tonight at 9 watch the game",
    "this afternoon clean the kitchen", "buy eggs, call the bank and email Mr. Smith", "buy eggs and then call the bank",
    "put batteries on my shopping list", "add milk to the groceries list", "Friday at midnight submit the lab",
    "the day after tomorrow book the flight", "in 3 days renew the library books", "next weekend visit grandma",
    "remind me this weekend to mow the lawn", "an idea for the story: a lighthouse that moves",
    "I need to finish the slides", "don't let me forget to feed the cat", "todo: back up the server",
    "mark call the dentist as done", "tick off buy stamps", "I finished the essay",
    "move the dentist reminder to Friday", "push the essay to tomorrow at 4", "reschedule water plants to next Monday",
    "at the end of the day send the report", "remind me in the morning to take vitamins", "noon lunch with Sam",
    "remind me at 7 to call", "The quick brown fox jumps over the lazy dog and keeps on running far away into the forest until night falls",
    "uh", "remind me to stretch every 90 minutes", "every month on the 15th pay the card", "every 3 days check the mail",
    "on Mondays and Wednesdays go to the gym", "remind me on the 20th to pay the bill", "renew passport by March",
]

TASKS = [
    {"uid": "a1", "title": "Call the dentist", "remind_at": "2026-11-18T15:00"},
    {"uid": "a2", "title": "Essay", "due": "2026-11-21"},
    {"uid": "a3", "title": "Buy stamps"},
    {"uid": "a4", "title": "Water plants", "due": "2026-11-19T10:00", "repeat": "FREQ=WEEKLY;INTERVAL=2"},
]
PLACES = ["home", "the library", "Sarah's"]

# what an LLM might hand back, checked against the words (settle)
SETTLE = [
    ({"type": "task", "title": "Call mom", "remind_at": "2026-11-18T17:00"}, "remind me tomorrow at 5 to call mom"),
    ({"type": "task", "title": "Call mom", "remind_at": "2026-11-21T17:00"}, "remind me Thursday at 5 to call mom"),
    ({"type": "task", "title": "Check oven", "remind_at": "2026-11-17T08:00"}, "remind me in 20 minutes to check the oven"),
    ({"type": "task", "title": "Email", "due": "2026-11-20"}, "remind me to send the email by Friday"),
    ({"type": "task", "title": "Send report", "remind_at": "2026-11-25T17:00"}, "at the end of the day send the report"),
    ({"type": "task", "title": "Lab", "remind_at": "2026-11-21T00:00"}, "Friday at midnight submit the lab"),
    ({"type": "task", "title": "Piano", "remind_at": "2026-11-17T16:00", "repeat": "FREQ=WEEKLY;BYDAY=TU,TH"}, "every Tuesday and Thursday at 4 practice piano"),
    ({"type": "task", "title": "Card", "remind_at": "2026-11-17T09:00", "repeat": "FREQ=MONTHLY"}, "pay the card on the 15th of every month"),
    ({"type": "task", "title": "Vitamins"}, "remind me to take vitamins at 8"),
    ({"type": "task", "title": "Noon", "remind_at": "2026-11-16T12:00"}, "remind me at noon to eat"),
    ({"type": "task", "title": "Gift", "due": "2026-12-20"}, "buy a gift before christmas"),
]

# items after settle, as tidy_repeats gets them (one capture's worth each)
DOG = {"type": "task", "title": "Feed the dog", "repeat": "FREQ=DAILY;INTERVAL=2"}
TIDY = [
    [dict(DOG, remind_at="2026-11-17T07:30"), dict(DOG, remind_at="2026-11-17T18:00", notes="the wet food")],
    [{"type": "task", "title": "Meds", "remind_at": "2026-11-17T08:00", "repeat": "FREQ=DAILY", "times": ["20:00", "8:00", "nope"]}],
    [{"type": "task", "title": "Meds", "remind_at": "2026-11-17T20:00", "repeat": "FREQ=DAILY"},
     {"type": "task", "title": "meds", "remind_at": "2026-11-18T08:00", "repeat": "FREQ=DAILY"}],
    [{"type": "task", "title": "Gym", "remind_at": "2026-11-16T07:00", "repeat": "FREQ=WEEKLY;BYDAY=MO"},
     {"type": "task", "title": "Gym", "remind_at": "2026-11-19T07:00", "repeat": "FREQ=WEEKLY;BYDAY=TH"}],
    [{"type": "task", "title": "Stretch", "remind_at": "2026-11-17T07:00", "repeat": "FREQ=DAILY"}],
    [{"type": "task", "title": "Pills", "remind_at": "2026-11-17T07:00", "repeat": "FREQ=DAILY;COUNT=7"}],
    [{"type": "task", "title": "Report", "due": "2026-11-17", "remind_at": "2026-11-17T07:00", "repeat": "FREQ=WEEKLY"}],
    [{"type": "task", "title": "Ping", "remind_at": "2026-11-17T07:00", "repeat": "FREQ=HOURLY;INTERVAL=3"},
     {"type": "note", "title": "An idea"}, {"type": "task", "title": "Once", "remind_at": "2026-11-17T09:00"}],
]

# repeating tasks ticked off, for roll()
ROLL = [
    {"type": "task", "title": "Feed the dog", "remind_at": "2026-11-17T07:30", "repeat": "FREQ=DAILY;INTERVAL=2",
     "times": ["07:30", "18:00"], "done_at": 1},
    {"type": "task", "title": "Meds", "remind_at": "2026-11-17T08:00", "repeat": "FREQ=DAILY;COUNT=4",
     "times": ["08:00", "20:00"], "done_at": 1},
    {"type": "task", "title": "Piano", "remind_at": "2026-11-17T16:00", "repeat": "FREQ=WEEKLY;BYDAY=TU,TH", "done_at": 1},
]


def roll_rows():
    out = []
    for r in ROLL:
        d = dict(r, remind_at=int(hub.parse_local(r["remind_at"]).timestamp()))
        out.append(d)
    return out


def task_rows():
    out = []
    for i, t in enumerate(TASKS):
        d = {"type": "task", "title": t["title"]}
        if "remind_at" in t:
            d["remind_at"] = int(hub.parse_local(t["remind_at"]).timestamp())
        for k in ("due", "repeat"):
            if k in t:
                d[k] = t[k]
        out.append({"id": f"t{i + 1}", "uid": t["uid"], "data": d})
    return out


def main():
    hub.TZ = ZoneInfo(ZONE)
    cases = {"zone": ZONE, "tasks": None, "places": PLACES, "runs": []}
    for now in NOWS:
        fixed = datetime.fromisoformat(now).replace(tzinfo=hub.TZ)
        hub.local_now = lambda fixed=fixed: fixed
        tasks = task_rows()
        cases["tasks"] = tasks
        recs = [(t["id"], {"uid": t["uid"], "data": t["data"]}) for t in tasks]
        run = {"now": now, "parse": [], "settle": [], "said_time": [], "said_span": [], "repeat": [], "tidy": [], "roll": []}
        for p in PHRASES:
            run["parse"].append([p, hub.basic_parse({"type": "voice", "text": p}, PLACES, recs)])
            run["said_time"].append([p, hub.said_time(p)])
            span = hub.said_span(p)
            run["said_span"].append([p, None if span is None else int(span.total_seconds())])
            run["repeat"].append([p, list(hub.pull_repeat(p))])
        for it, text in SETTLE:
            run["settle"].append([it, text, hub.settle(dict(it), text, fixed)])
        for items in TIDY:
            run["tidy"].append([items, hub.tidy_repeats(copy.deepcopy(items), fixed)])
        for d in roll_rows():
            run["roll"].append([d, hub.roll(dict(d), fixed)])
        run["parse"].append(["", hub.basic_parse({"type": "screenshot", "app": "com.android.chrome", "screen_text": "Hello"})])
        cases["runs"].append(run)
    out = Path(__file__).resolve().parent.parent / "android/app/src/test/resources/plain-cases.json"
    out.write_text(json.dumps(cases, indent=1, ensure_ascii=False) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
