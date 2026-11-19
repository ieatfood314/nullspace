# Nullspace for Android

The app itself: dictation, captures, reminders and the list, all working on
the phone alone, with [the hub](../README.md#the-hub-optional) as an
option. Dictation started as Squelch for Android, and Nullspace replaces it
there. Only one app can own the key, and the speech model takes about 1 GB
of RAM whichever app loads it.

## Dictation

- **Models:** Parakeet TDT 0.6B v2 (English, the default), Parakeet v3
  (English and 24 other European languages) or Parakeet 110M (English, about
  five times quicker, less accurate), all through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and downloaded from
  Hugging Face when you pick them. Any other sherpa-onnx transducer on
  Hugging Face works too: type its `user/name` under Model.
- **Speed:** on a Dimensity 7300 (CMF Phone 2 Pro), 7.4 s of speech decodes
  in about 0.65 s. Finished sentences are decoded while you're still
  talking.
- **Typing:** through the accessibility service. Plain fields get the text
  set at the cursor, so the clipboard is left alone. Web editors other than a
  plain `<input>` (Obsidian, Gmail, a `<textarea>`) get it pasted, so their
  markup survives and it lands where the editor's own cursor is.
- **Cleanup:** filler words removed and your dictionary applied, the same as
  desktop Squelch.
- **Tidy:** with an AI key (or a hub), a dictation of 8 words or more has its
  repeats, false starts and self-corrections cut (`Brain.tidy`, 4 s at
  most). A check throws the answer away if it added words or cut too much,
  so the worst case is the text as heard.
- **Cut-off starts:** if you were already talking when the mic opened, the
  missing first words are guessed the same way.
- **Memory:** when Android runs low the model is unloaded, and the next
  dictation loads it again while you talk (about 3 s).

## Captures

- **Press:** a screenshot (scaled to 720 px wide, WebP) and the text on
  screen, read from the accessibility tree rather than OCR. The pill only
  appears once a press turns into a hold, so it's never in the shot.
- **Press, then hold:** a voice note, transcribed on the phone.
- **Share** text or an image to Nullspace from any app.

Each capture becomes tasks and notes, and the phone says what it made
("Added Call the dentist"). With no hub the phone files it itself
(`Filer`): through your AI key if there is one (`Brain`, the hub's prompt),
else with the built-in parser (`Plain`, a port of the hub's). With a hub,
the hub files it.

### The Essential key on Nothing OS

Nothing's own handler sees the key before any app can stop it, so it
still opens Essential Space (tap) or the recorder (hold). Turn those apps off
over adb (Nullspace replaces both):

```
adb shell pm disable-user --user 0 com.nothing.ntessentialspace
```

```
adb shell pm disable-user --user 0 com.nothing.ntessentialrecorder
```

Essential Voice (hold while the keyboard is up) has its own switches:

```
adb shell settings put secure nt_essential_voice_action_hold 2
```

```
adb shell settings put secure nt_essential_voice_action_double_click 2
```

To undo, `pm enable` the two apps and set both settings back to `0` and `1`.

## Build

JDK 17 and the Android SDK (platform 35). The sherpa-onnx AAR is fetched and
checksum-checked on the first build.

```
./build.sh
```

Signing and first-run defaults are read from `~/.config/nullspace/` (or
`~/.config/uplink/`): `keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) and `android.properties` (`hub=`, `squelch_hub=`,
and `donate_url=`, where opt-in training data goes; without it the setting
is hidden). Without them the release is signed with the debug key and has
no defaults. `-Phub=http://10.0.2.2:4645`
points a build at a hub on the emulator's host.

`./release.sh` builds the committed, pushed HEAD and publishes it as GitHub
release `v<uplinkVersion>.<commit count>`.
