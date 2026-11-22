# Nullspace ultra.2 — fast selective accuracy

This is an **ultra prerelease** focused on better ramblers without giving up
Wispr-level latency. Main, stable, and AUR are unchanged. The UI is unchanged.

## What changed

- **Desktop auto recognition:** clips under 7 seconds use Parakeet; clips of 7
  seconds or longer use Whisper Turbo. Explicit Parakeet or Whisper selection
  still overrides auto mode. No clip is transcribed twice.
- **Mobile:** Parakeet is the default again. Whisper Turbo remains optional,
  not a 1 GB requirement.
- **Sentence-restart editor:** handles cases such as
  `For example, that squelch, the newest squelch.` →
  `For example, the newest squelch.` It only cuts incomplete comma-delimited
  fragments with conservative overlap checks. Negation, numbers, ordinary
  clauses and intentional repetition are protected.
- **Personal corrections:** the reviewed personal dictionary is included on
  both platforms. Android merges rather than overwrites existing entries.
- **Guarded stutter cleanup:** exact stutters and earlier content-bearing
  repeated phrases, with sentence-boundary and short-command guards.
- **No default AI rewriting:** Groq/LLM cleanup hurt accuracy in testing and is
  off. Parakeet remains available as a fallback everywhere.

## Measured gain

The complete pipeline (selective recognizer + restart editor + guarded stutter
cleanup) was evaluated on 250 independent DisfluencySpeech test clips:

| Metric | Parakeet + previous cleanup | ultra.2 |
| --- | ---: | ---: |
| WER | 12.36% | **10.83%** |
| Exact clips | 49/250 | **59/250** |
| Median recognition + cleanup | 0.196 s | **0.198 s** |
| Mean recognition + cleanup | 0.196 s | **0.609 s** |
| p95 recognition + cleanup | 0.241 s | **1.375 s** |

That is **12.4% fewer word errors**. For comparison, the earlier Wispr replay
had 0.794 s median and 1.526 s p95, so ultra.2 is faster on median, mean and
p95. Long clips pay for Turbo; short clips stay at Parakeet speed.

The restart rule fixes the requested `that squelch, the newest squelch`
example and makes no external-corpus edits by itself. It is deliberately not a
general sentence-rewrite model.

## Personal accuracy and validation

The earlier full-Turbo personalized pipeline reached 0% fitted WER on 20 known
corrections and 2.58% leave-one-out WER. Those figures are not claims about
this selective pipeline's unseen accuracy. The three newest personal rules
change only their intended clips in the 506-recording private corpus.

Twelve desktop unit tests and 28 Android JVM tests pass. The optional Android
Whisper decoder also passed its real x86_64 test, but it is slow (6.7 seconds
of audio in 10.99 seconds) and is not the mobile default. Physical arm64
performance remains unmeasured.

## Artifacts

- Android: `v1.0.77-ultra.2`, versionCode 77
- Desktop: `desktop-v1.1.9-ultra.0`
- Engine: Squelch `v0.14.0-ultra.1`

Published only as GitHub ultra prereleases. Nothing is installed automatically,
and no main/stable promotion or AUR update is implied.
