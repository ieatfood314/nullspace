# Uplink

One place for the things you mean to do. Press a key on your phone and Uplink
keeps what's on screen; hold it and talk, and it files what you said. It all
lands in one list, shared with the to-do apps you already use and every
device on your network.

- **Phone app** (Android). It replaces Squelch for Android: the same Parakeet
  dictation, plus captures, reminders and the list.
- **Hub** (`server/uplink.py`). A small Python server for your home machine.
  It holds the data, files captures, and keeps CalDAV task lists (Radicale)
  and Ignition in step.
- **Web page** (`web/`). The hub serves it, and the phone app bundles the
  same page.

## The key

On Nothing and CMF phones this is the Essential key. Any key works; set it
in the app.

| Press | Does |
|---|---|
| Hold | Dictation into the field you're typing in |
| Press | Keeps a screenshot and the text on screen |
| Press, then hold once the card shows | A voice note about the screenshot |
| Press, then hold | A voice note |
| Double press | Opens Uplink |

A voice note like "remind me to call the dentist tomorrow at 3" or "when I
get home, take the bins out" becomes a task with a timed or place reminder.
"Every Tuesday and Thursday at 4", "every 2 weeks", "every day for the next
week" or "every 11.46 hours" becomes one repeating task.

A voice note can also change a task you already have: "mark call the dentist
as done", "move the dentist reminder to Friday", "push the essay back a day",
"cancel the reminder for the essay" or "delete the dentist task". Redo on that
capture puts the task back as it was, unless you've changed it since.

## Privacy

- **Your voice stays on the phone.** Parakeet runs on the phone's CPU
  through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Only the text
  it hears is sent to your hub.
- **Screenshots** are kept on the phone and copied to the hub over Wi-Fi.
  They're never sent anywhere else.
- **Text goes to an LLM.** To turn a capture into tasks and notes, the hub
  sends its text (what you said, or the text on screen) to an
  OpenAI-compatible API, along with the titles and times of up to 60 open
  tasks so that a voice note can change one. It's set up for OpenRouter's
  free models. With no key, or once the free quota runs out, a plain
  built-in parser files it instead.
- **Categories.** Each open task's title, notes and list name are sent to
  TypeSafe to pick its category, or to the same LLM when there is no
  TypeSafe key. With neither key, nothing is sent and tasks stay under
  Other.
- **Address search** goes through the hub to OpenStreetMap's Nominatim,
  with a search area around your rough location (rounded to about 1 km) so
  nearby places come first. "Use where I am" sends the exact spot the same
  way, to name the street it's on; if that fails the place shows its
  coordinates.

## Mobile data

The phone app keeps under 10 MB a month on mobile data, counting every
request plus an allowance for VPN overhead. On mobile data it:

- checks in at most once an hour, plus whenever you make something (an empty
  check is about 2 KB);
- uploads no screenshots and fetches nothing big;
- past 8 MB, only sends things you made;
- past 9.5 MB, sends nothing until Wi-Fi or the next month.

The meter is under Settings, Mobile data.

## Reminders

- **Time:** exact alarms on the phone.
- **Place:** geofences, through Google Play services. Places are named
  ("Home", "the library"). Set where they are in the Places tab, either by
  address search or "Use where I am". If a voice note names a place Uplink
  doesn't know yet, it adds it without a location until you set one.
- A reminder fires once. It has a Done button, and changing the time or
  place arms it again.
- **Repeats:** any number of minutes, hours, days, weeks, months or years,
  on set weekdays if you like, with an optional end. A repeating task
  reminds at each of its times whether or not you ticked the last one off.
  Ticking it off moves it to its next time after now. tasks.org sees the
  same repeat.

## Hub

```sh
pip install vobject
python3 server/uplink.py --port 4645
```

Configured by environment variables:

| Variable | What |
|---|---|
| `UPLINK_DATA` | data folder (default `~/.local/share/uplink`) |
| `UPLINK_PORT`, `UPLINK_HOST` | where to listen (default `127.0.0.1:4645`) |
| `UPLINK_RADICALE_DIR` | a Radicale user's collection folder, e.g. `~/.var/lib/radicale/collections/collection-root/<user>` |
| `UPLINK_RADICALE_LOCK` | Radicale's lock file, if it isn't `collections/.Radicale.lock` above that |
| `UPLINK_IGNITION_URL` | an Ignition server, e.g. `http://127.0.0.1:4580` |
| `UPLINK_LLM_KEY` or `UPLINK_LLM_KEY_FILE` | API key for the LLM |
| `UPLINK_LLM_URL` | chat completions URL (default OpenRouter) |
| `UPLINK_LLM_MODELS` | comma-separated models to try in order (default: a few free OpenRouter models) |
| `UPLINK_TYPESAFE_KEY` or `UPLINK_TYPESAFE_KEY_FILE` | TypeSafe API key; with it, open tasks get their category from TypeSafe's Jev instead of the LLM |
| `UPLINK_TYPESAFE_MODEL` | model for categories (default `jev-latest`) |
| `UPLINK_TYPESAFE_URL` | TypeSafe endpoint (default `https://api.typesafe.ai/v1/systemone`) |
| `UPLINK_FONTS` | a folder with `ndot55.otf`, served to the page (Ndot isn't open-licensed, so it isn't included; without it the page uses Inter) |
| `UPLINK_TZ` | time zone for "tomorrow at 3" (default the system's) |

`systemd/uplink.service` is a user unit for it; it reads
`~/.config/uplink/hub.env`.

The hub has no login. Run it on a private network, such as a tailnet, and
don't expose it to the internet.

### Radicale

Each Radicale task list (VTODO calendar) is a list in Uplink, both ways.
Lists made in Uplink become new task lists, so tasks.org and other CalDAV
clients pick them up on their next sync. The hub reads and writes
Radicale's files directly while holding Radicale's own lock, so Radicale
can keep running.

### Ignition

Ignition's need and want lists show up as the "Need to do" and "Want to do"
lists, both ways. The first sync only reads from Ignition and matches tasks
by title, so nothing is duplicated.

## Phone app

Get the APK from [Releases](https://github.com/ieatfood314/uplink/releases),
or add this repo to Obtainium. In Uplink's settings:

1. **Accessibility:** turn on Uplink. It needs this to see the key, type
   dictation and take screenshots.
2. **Microphone, Notifications, Location** (all the time, for place
   reminders): allow.
3. **Model:** pick one (English is the usual choice). It's about 600 MB,
   downloaded once from Hugging Face.
4. **Hub:** your hub's address, like `http://server:8448`.

Sharing text or an image to Uplink from any app saves it as a capture.

**Words sync** shares your dictionary and dictation stats with desktop
[Squelch](https://github.com/ieatfood314/squelch). It only runs on Wi-Fi.

See [android/README.md](android/README.md) for dictation details and
building.

## Data

Every record is `(kind, uid, data, updated_at, deleted)`. The newest
`updated_at` wins, for the whole record. Clients call `POST /api/sync` with
`{since, records}` and get back `{rev, records}` with everything changed
after `since`. Request and response bodies may be gzipped.

| Kind | Fields |
|---|---|
| `list` | `name`, `sort`, `color`, `inbox`, `radicale`, `ignition` |
| `item` | `type` (task or note), `title`, `notes`, `list`, `due`, `remind_at`, `place`, `place_on` (arrive or leave), `repeat` (an iCalendar RRULE such as `FREQ=WEEKLY;BYDAY=TU,TH`; FREQ, INTERVAL, BYDAY, COUNT and UNTIL are used), `done_at`, `fired_at`, `place_fired_at`, `capture`, `category` (work, chores, projects, fun or other), `category_auto` (set when the hub picked the category: a signature of the title, notes and list it judged; absent when you picked it, and the hub then leaves it alone) |
| `place` | `name`, `lat`, `lon`, `radius` (metres), `address` |
| `capture` | `type` (voice, screenshot, text, share), `text`, `voice` (a screenshot with a voice note), `screen_text`, `app`, `image`, `list` (typed while that list was open; tasks go there unless the filer picks one), `status` (new or done), `summary`, `items`, `changed` (tasks it changed: `uid`, `title`, `before`, the task as it was, and `sig`, so Redo only puts it back if nothing changed it since) |

Other endpoints: `GET /api/status`, `GET /api/geocode?q=`,
`GET /api/reverse?lat=&lon=`,
`POST /api/shot/<uid>` (a WebP), `POST /api/reprocess/<uid>`,
`GET /shots/<uid>.webp`.

## License

MIT, see [LICENSE](LICENSE). Third-party parts are listed in
[android/NOTICE.md](android/NOTICE.md).
