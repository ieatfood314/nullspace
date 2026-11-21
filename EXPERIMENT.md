# Nullspace ultra transcription experiment

This is an **ultra prerelease channel** for the transcription experiment. It does
not promote anything to `main`, `stable`, or AUR.

## Included

- Squelch `v0.13.0-ultra.0` for the Linux dictation engine.
- Parakeet English v2 by default on Linux; Android already defaults to its
  accurate 0.6B English v2 model.
- Narrow contextual correction learning from explicit user edits.
- Local stutter and explicit `no/sorry` self-correction handling without a
  network request, including negation and intentional-emphasis guards.
- A conservative vocabulary-aware AI prompt, with the existing one-second
  mobile polish deadline unchanged. Network AI tidy is opt-in on Linux.
- No changes to tasks, notes, sync APIs, recording formats, or training-data
  opt-in behavior.

## Accuracy and speed evidence

The private benchmark has 506 recordings. On the 20 corrected clips, the fitted
local candidate reaches 0% normalized WER and leaves all 101 accepted reference
clips unchanged. This is personalization fitted to those corrections, **not a
general 90% accuracy claim**. Leave-one-out remains 3.351% WER, about 36% below
the original 17-clip baseline.

Linux full-pipeline latency is effectively tied at median (0.216 s versus
0.212 s), with faster p95 and maximum latency. Full-corpus LLM cleanup was too
slow, so it is optional. Mobile accuracy was not separately measured; the phone
keeps its existing model and one-second polish deadline.

Wispr Flow was used only for comparison, never as the correction label.

## Release contents

- `v0.13.0-ultra.0` in `ieatfood314/squelch`.
- `desktop-v1.1.7-ultra.0` in `ieatfood314/nullspace`.
- `v1.0.72-ultra.0` in `ieatfood314/nullspace`, carrying the Android APK and
  experimental desktop package.
- `experiment-dictionary.json` is a release asset rather than private training
  data committed to a public source repository. Review it before using it.

Do not install the standalone `squelch` package on a machine running Nullspace.
Use the Nullspace package from the ultra release.
