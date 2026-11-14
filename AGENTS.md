# Notes for coding agents

The README covers what Uplink does and how the data looks. This file covers where it runs and how it ships. Install-specific facts (addresses, ports on the tailnet) are in the fleet skill, not here; this repo is public.

## Where things run
- **Repo:** `~/repos/uplink` on nas (T3 project "uplink").
- **Hub:** the `uplink` user unit on nas, from `systemd/uplink.service`, running `server/uplink.py` straight from the repo, so a change ships with `systemctl --user restart uplink`. Config is in `~/.config/uplink/hub.env`, and data is in `~/.local/share/uplink/` (SQLite plus `shots/`).
- **Live data it writes to:** Radicale's collection files (tasks.org syncs them) and Ignition's API. Before changing the bridges, test against copies: copy the Radicale user folder and Ignition's DB to /tmp, run a second Ignition (`ignition.py --port 4697 --db <copy>`) and a second hub (`UPLINK_RADICALE_DIR=<copy> UPLINK_IGNITION_URL=http://127.0.0.1:4697 python3 server/uplink.py --port 4698 --data /tmp/...`). Kill them with a bracketed pattern (`pkill -f 'uplink.py --por[t] 4698'`).
- **LLM:** the free pool on nas (`UPLINK_LLM_URL`, `UPLINK_LLM_MODELS=free,free-fast` and the client key in `hub.env`; the fleet skill's free-pool file has its providers and limits). When no provider answers, the plain parser files captures, and Redo on a capture tries again. OpenCode Zen's free models refuse non-OpenCode clients, so don't switch to them.
- **Categories:** the hub picks each open task's category. With a TypeSafe key in `~/.config/uplink/typesafe_key` (`UPLINK_TYPESAFE_KEY_FILE` in `hub.env` points at it) it asks Jev, one request per task. Without that file it asks the filing LLM about all waiting tasks in one request, at most every 5 minutes. The key file is read on every use, but the engine's thread only starts at boot if there is some key, so restart the hub after adding the first one. The TypeSafe console blocks automated browsers (Cloudflare), so only Aidan can make that key.

## Android
- **Build on regolith, not nas.** Gradle and the emulator starve nas (7.7 GB RAM), and a Gradle run on nas was OOM-killed. rsync `android/` and `web/` to `regolith:build/uplink/` (exclude `build`, `.gradle`, `local.properties`, `app/libs`), then `ssh regolith 'cd ~/build/uplink/android && nice -n 15 ./build.sh'`. The signing key and `android.properties` are in `~/.config/uplink/` on both hosts. regolith's copy has `storeFile` under `/home/aidan-heeney`.
- **Emulator:** AVD `sq35` on regolith, started headless (`-no-window`), so nothing pops up on Aidan's desktop. Point a build at a test hub with `-Phub=http://10.0.2.2:<port>` and an `ssh -R` tunnel. To fake the key, set prefs `scan_code=88` and `sendevent /dev/input/event1 1 88 1|0`. regolith's zsh doesn't word-split `$A`, so use a wrapper script for adb. Anything that dictates needs `./build.sh -Pemu`: the release APK is arm64-only, and onnxruntime segfaults under the emulator's ARM translation. A real dictation on the emulator also syncs its stats to the live Squelch hub, so delete that device's `stats/<id>.jsonl` and its `devices.json` entry afterwards (stop the hub first, since it holds them in memory).
- **The page is `web/`,** copied into the APK at build time. Change it once and both the hub and the phone get it. The phone loads it from `https://appassets.androidplatform.net/web/` and talks to the native side through `UplinkNative`.
- **Mobile data budget:** 10 MB a month is Aidan's hard rule. Anything new that talks to the network has to go through `HubSync.post` (which counts it) and skip big transfers when `Net.metered`.

## Releasing the APK
Versions are `uplinkVersion` (gradle.properties) plus the commit count, so commit and push first. Then, on regolith in a clean clone (`~/build/uplink-git`, `git pull`), run `android/release.sh "notes"`. If regolith's `gh` isn't logged in, build there and run `gh release create v<version> <apk>` from nas instead. Obtainium on halley tracks the GitHub releases.

## Squelch
Uplink replaced Squelch for Android (same dictation code, forked from `squelch/android`). Desktop Squelch is separate and still in use. Uplink's Words sync talks to the Squelch hub, Wi-Fi only.
