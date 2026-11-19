# Nullspace

**The Essential key, done properly.** One key on your phone catches
everything you mean to do: hold it and talk to type anywhere, press it to
keep what's on screen, press and hold to leave yourself a voice note. It all
becomes tasks, notes and reminders in one list, a second after you let go.

It's a free, open-source replacement for Nothing's Essential Space and
Essential key, and it runs on any Android phone, Nothing or not.

## Why it's better

- **Your voice notes don't sit "analyzing".** Nullspace files a voice note
  in about a second. "Remind me to call the dentist tomorrow at 3" is a task
  with an alarm before you've put the phone down. With no internet it still
  files it, on the phone, at once.
- **"When I get home, take the bins out."** Place reminders, from a voice
  note. Say where and when, and it fires when you arrive or leave.
- **It changes things, not just adds them.** "Move the dentist to Friday",
  "push the essay back a day", "mark the bins as done", "every Tuesday and
  Thursday at 4". A tap on Redo undoes it.
- **Dictation that types anywhere.** Hold the key in any text box. Speech
  is turned into text on the phone by Parakeet, one of the most accurate
  open speech models, so your voice never leaves it. About 0.6 s for a
  sentence on a mid-range phone.
- **It cleans up your rambling.** Repeated words, false starts and "on
  Tuesday, no, Wednesday" are cut, and your own words are kept. Turn it off
  if you like it raw.
- **Real sync.** Run the optional hub on any computer, and your phone,
  laptop and desktop share one list.
- **On your desktop too.** The same key and the same gestures on Linux,
  with dictation on your graphics card.
- **Yours.** No account, no cloud, no subscription. Your list lives on your
  phone (and your hub, if you run one). MIT licensed.
- **Your look.** Pick the accent color, the key, the speech model and your
  own words it should always spell right.

## Install

About five minutes.

### 1. Get the app

Easiest, with updates: install [Obtainium](https://obtainium.imranr.dev),
tap **Add app** and paste `https://github.com/ieatfood314/nullspace`.

Or download the newest `nullspace-<version>.apk` from
[Releases](https://github.com/ieatfood314/nullspace/releases), open it,
and allow your browser to install apps when Android asks.

### 2. Set it up

Open Nullspace. Settings opens by itself the first time. Tap each row that
has a blue action on the right:

1. **Accessibility: Turn on.** Find Nullspace in the list, turn it on, and
   allow it. This is how it sees the key, types dictation into other apps
   and takes screenshots.
2. **Microphone: Allow.**
3. **Model: pick one.** "English" is the right choice for most people. It's
   about 600 MB, downloaded once over Wi-Fi.
4. **Key.** On Nothing and CMF phones it's the Essential key already. On
   other phones tap **Change** and press the key you want: a spare side
   button, or volume down if your phone has nothing else.
5. **Notifications** and **Location: Allow.** Location is only used for
   place reminders. Pick **Allow all the time**, or they can't fire.

Now hold the key and say something into the "Try it here" box.

### 3. Add an AI key (free, two minutes)

Without one, Nullspace still files everything with its built-in parser,
which handles reminders, times, repeats and places. With a key it also
understands messier notes, makes sense of what was on screen, sorts tasks
into categories and tidies your dictation. Groq is free, needs no card and answers in well
under a second, so that's the one to use.

1. On any device, go to [console.groq.com](https://console.groq.com) and
   sign in with Google, GitHub or your email.
2. In the menu on the left, open **API Keys**.
3. Tap **Create API Key**. Name it `Nullspace` and tap **Submit**.
4. Copy the key. It starts with `gsk_`, and Groq only shows it once.
5. In Nullspace: **Settings → AI → Set up**. Leave **Groq** picked, paste
   the key into the key box and tap **Save**. The row says **Groq** when
   it works.

That's it. Leave the model box empty and Nullspace picks a good one. Any
other OpenAI-compatible service works too (OpenRouter, a local Ollama or
llama.cpp server): pick **Other** and paste its chat completions URL, key
and model.

### On a Nothing phone

Nothing's own handler sees the Essential key before any app can, so it
would still open Essential Space. Turn it off once, from a computer with
[adb](https://developer.android.com/tools/releases/platform-tools)
(Settings → About phone, tap the build number seven times, then turn on USB
debugging under Developer options). Run each of these:

```
adb shell pm disable-user --user 0 com.nothing.ntessentialspace
```

```
adb shell pm disable-user --user 0 com.nothing.ntessentialrecorder
```

```
adb shell settings put secure nt_essential_voice_action_hold 2
```

```
adb shell settings put secure nt_essential_voice_action_double_click 2
```

To undo, `pm enable` the two apps and set the two settings back to `0` and
`1`.

## The key

| Press | Does |
|---|---|
| Hold | Dictation into the field you're typing in |
| Press | Keeps a screenshot and the text on screen |
| Press, then hold once the card shows | A voice note about that screenshot |
| Press, then hold | A voice note |
| Double press | Opens Nullspace |

Sharing text or an image to Nullspace from any app saves it too.

## What it understands

- **Times:** "tomorrow at 3", "Friday morning", "in 20 minutes", "at 140",
  "on the 14th".
- **Places:** "when I get home", "when I leave work", "at the library".
  Places it doesn't know yet are added; set where they are in the Places
  tab, by address search or "Use where I am".
- **Repeats:** "every Tuesday and Thursday at 4", "every 2 weeks", "feed
  the dog every second day at 7:30 and at 6", "every day for the next
  week", "every 11.46 hours". Ticking one off moves it to its next time.
- **Changes:** "mark X as done", "move X to Friday", "push X back a day",
  "cancel the reminder for X", "delete X".

## Privacy

- **Your voice stays on the phone.** Speech is turned into text there and
  the audio is never uploaded, unless you turn on sharing (below).
- **Only text goes to your AI**, and only if you added a key: what you
  said or typed, the text on screen for a screenshot capture, and the
  titles and times of up to 60 open tasks so a voice note can change one.
  Dictation tidying sends just that dictation. Screenshots themselves are
  never sent to it.
- **Address search** goes to OpenStreetMap's Nominatim, with your rough
  location (rounded to about 1 km) so nearby places come first.
- **No analytics, no tracking.** The app talks to Groq (or your own AI),
  Nominatim, Hugging Face (to download a model), and your own hubs if you
  set them up. Nothing else.

### Share training data (opt in)

You can choose to help make the speech models better: **Settings → Share
training data → Turn on** sends your dictation audio, what the model heard
and the fixes you made afterwards to the project, on Wi-Fi only. It's off
unless you turn it on, the text that was already in the box is never sent,
and turning it off deletes whatever was still waiting to go.

## Mobile data

Nullspace keeps under a monthly cap on mobile data, 10 MB unless you change
it under **Settings → Mobile data** (0 means Wi-Fi only). Every request is
counted, plus an allowance for VPN overhead. On mobile data it:

- checks in with your hub at most once an hour, plus whenever you make
  something (an empty check is about 2 KB);
- uploads no screenshots and fetches nothing big;
- past 80% of the cap, only sends things you made;
- past 95%, sends nothing until Wi-Fi or next month.

## On your computer (Linux)

The same key, on your desktop: hold it to dictate, press it to keep what's
on screen, press then hold for a voice note, double press to open your
list. It all lands in the same list as your phone's.

Nullspace for Linux runs on KDE Plasma (Wayland) and uses
[Squelch](https://github.com/ieatfood314/squelch) for the speech, which
runs on your graphics card, so a sentence types in a fraction of a second.
On Arch:

```
yay -S nullspace
```

Then open **Nullspace** from the app menu. The first time, Squelch's setup
opens too: give it keyboard access, download the speech model and pick
your key (Menu by default). After that, everything is in the tray icon:

- **Settings → Hub:** "This computer" keeps your list here, with a hub of
  its own (paste a Groq key under **AI key**, as on the phone). To share
  one list with your phone, run the hub on one machine and pick "Another
  computer" on the rest, with its address, plus **Settings → Hub** on the
  phone.
- **Dictation** opens Squelch's settings: the speech model, the key, your
  own words, typing as you speak.
- **Reminders on this computer** shows your reminders as notifications,
  with a **Done** button.

The text of a screen capture is read through accessibility. For Chromium
and Electron apps (VS Code, Obsidian, Discord) start them with
`--force-renderer-accessibility`, or Nullspace only sees the picture.

## Squelch

[Squelch](https://github.com/ieatfood314/squelch) is the dictation for
your Linux desktop, and it's also its own app: install just Squelch if all
you want is to hold a key, talk, and have it type. With Nullspace
installed, Squelch's tray icon steps aside and the key does Nullspace's
gestures.

Point **Settings → Words sync** on the phone at your Squelch hub, and the
words you taught either one, and your dictation stats, are shared between
them (Wi-Fi only).

## The hub (optional)

The phone does everything on its own. Run the hub when you want your list
on more than one device. It's one Python file with no dependencies, and it
serves the same page the phone uses, for your computers.

```sh
git clone https://github.com/ieatfood314/nullspace
```

```sh
python3 nullspace/server/nullspace.py --port 4645
```

Then put `http://<that computer>:4645` into **Settings → Hub** on the
phone, and open the same address in a browser. With a hub, it files
captures instead of the phone, using its own LLM settings below.

The hub has no login. Run it on a private network, such as a
[Tailscale](https://tailscale.com) tailnet, and don't expose it to the
internet. `systemd/nullspace.service` is a user unit for it.

Configured by environment variables (the older `UPLINK_*` names still
work):

| Variable | What |
|---|---|
| `NULLSPACE_DATA` | data folder (default `~/.local/share/nullspace`) |
| `NULLSPACE_PORT`, `NULLSPACE_HOST` | where to listen (default `127.0.0.1:4645`) |
| `NULLSPACE_LLM_KEY` or `NULLSPACE_LLM_KEY_FILE` | API key for the LLM |
| `NULLSPACE_LLM_URL` | chat completions URL (default OpenRouter; for Groq, `https://api.groq.com/openai/v1/chat/completions`) |
| `NULLSPACE_LLM_MODELS` | comma-separated models to try in order; ones with `fast` in the name are used for tidying and guessing |
| `NULLSPACE_TYPESAFE_KEY` or `NULLSPACE_TYPESAFE_KEY_FILE` | TypeSafe API key; with it, open tasks get their category from TypeSafe's Jev instead of the LLM |
| `NULLSPACE_TYPESAFE_MODEL` | model for categories (default `jev-latest`) |
| `NULLSPACE_TYPESAFE_URL` | TypeSafe endpoint (default `https://api.typesafe.ai/v1/systemone`) |
| `NULLSPACE_FONTS` | a folder with `ndot55.otf`, served to the page (Ndot isn't open-licensed, so it isn't included; without it the page uses Inter) |
| `NULLSPACE_TZ` | time zone for "tomorrow at 3" (default the system's) |

## Data

Every record is `(kind, uid, data, updated_at, deleted)`. The newest
`updated_at` wins, for the whole record. Clients call `POST /api/sync` with
`{since, records}` and get back `{rev, records}` with everything changed
after `since`. Request and response bodies may be gzipped.

| Kind | Fields |
|---|---|
| `list` | `name`, `sort`, `color`, `inbox` |
| `item` | `type` (task or note), `title`, `notes`, `list`, `due`, `remind_at`, `place`, `place_on` (arrive or leave), `repeat` (an iCalendar RRULE such as `FREQ=WEEKLY;BYDAY=TU,TH`; FREQ, INTERVAL, BYDAY, COUNT and UNTIL are used), `times` (a repeating task's times of day when it comes up more than once a day, such as `["07:30", "18:00"]`; COUNT counts each), `done_at`, `fired_at`, `place_fired_at`, `capture`, `category` (work, chores, projects, fun or other), `category_auto` (set when the filer picked the category: a signature of the title, notes and list it judged; absent when you picked it, and the filer then leaves it alone) |
| `place` | `name`, `lat`, `lon`, `radius` (metres), `address` |
| `capture` | `type` (voice, screenshot, text, share), `text`, `voice` (a screenshot with a voice note), `screen_text`, `app`, `image`, `list` (typed while that list was open; tasks go there unless the filer picks one), `status` (new or done), `summary`, `items`, `changed` (tasks it changed: `uid`, `title`, `before`, the task as it was, and `sig`, so Redo only puts it back if nothing changed it since) |

Other hub endpoints: `GET /api/status`, `GET /api/geocode?q=`,
`GET /api/reverse?lat=&lon=`, `POST /api/guess-start` and `POST /api/tidy`
(`{text}` in, `{text}` or `{text: null}` out), `POST /api/shot/<uid>` (a
WebP), `POST /api/reprocess/<uid>`, `GET /shots/<uid>.webp`.

See [android/README.md](android/README.md) for how dictation works and how
to build the app.

## License

MIT, see [LICENSE](LICENSE). Third-party parts are listed in
[android/NOTICE.md](android/NOTICE.md). Nullspace isn't made by or
affiliated with Nothing Technology; "Nothing", "Essential Space" and
"Essential key" are theirs.
