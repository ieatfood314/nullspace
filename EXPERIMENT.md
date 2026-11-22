# Nullspace accuracy-first ultra transcription experiment

This is an **accuracy-first ultra build**. It does not promote anything to
`main`, `stable`, or AUR, and the UI is unchanged. Latency was deliberately not
a constraint for this experiment; the normal releases keep their existing
speed rules.

## Included

- **Whisper Turbo** on Linux and Android. Desktop defaults to the local
  `ggml-large-v3-turbo-q5_0.bin` model. Android supports the Sherpa-ONNX int8
  Whisper Turbo export (1.04 GB), downloaded separately rather than bundled in
  the APK. Parakeet remains available as a fallback.
- The narrow contextual correction learner and an updated, reviewable personal
  dictionary. The dictionary is a separate package file, not hidden in code.
- The 553 KB Apache-2.0 DisfluencySpeech stutter model. It only removes exact
  stutters or earlier content-bearing repeated phrases, with sentence-boundary
  and short-command guards. It runs locally with no network request.
- Qwen cleanup was tested and **left off by default**: it did not improve the
  strong Turbo pipeline. Optional AI tidy has a 15-second ultra deadline and an
  as-heard fallback. The Android, desktop and hub prompts are identical.

## Accuracy evidence

### Your corrected recordings

Whisper Turbo plus the personalized dictionary reaches **0% normalized WER on
20 known corrections** (388 reference words). Whisper with the personalized
vocabulary also reaches 0% on the 19 of those covered by the original frozen
manifest. These are **fitted personal results**, not evidence that unseen
dictation will be perfect.

The leave-one-out estimate is **2.58% WER**: each correction is withheld while
the other corrections train the replacement layer. That is the more honest
generalization estimate and does **not** meet the 0.5% target. The guarded
stutter model changes none of the 506 private recordings. The three newest
personalization rules change only their three intended corrected clips.

### Independent DisfluencySpeech test, 250 clips

Scored against `transcript_c` after real speech recognition:

| Pipeline | WER | Exact clips |
| --- | ---: | ---: |
| Parakeet + handwritten cleanup | 12.36% | 49/250 |
| Whisper Turbo + handwritten cleanup | 10.04% | 74/250 |
| Whisper Turbo + guarded stutter model | **9.79%** | **75/250** |

Turbo plus the guarded model reduces WER by about **21%** relative to the
Parakeet pipeline. This is an external-corpus result, not a measurement on your
private corrections. Turbo's raw output is not directly comparable with
Parakeet's raw output because Whisper omits some disfluencies itself.

Groq Whisper and a blanket `gpt-oss-120b` editor were worse on the private
corrected set. Qwen preserved the fitted Turbo result but did not improve it,
and its partial external run also worsened WER, so network cleanup is not part
of the default accuracy path.

## Speed and validation

The 250-clip desktop Turbo run took 1.30 s median per complete recognition and
cleanup (p95 1.37 s). The guarded local cleanup itself adds well under 1 ms
median. The phone's int8 model is not identical to desktop's q5_0 model.

A focused Android 15 x86_64 emulator test passed using the real int8 weights.
The 6.7-second known clip decoded as "Gemini just released a bunch of new
models. Can I try out Neon and Hydrogen on the free plan?" Decode took 10.99 s;
model load and warm-up took 26.11 s. Physical arm64 accuracy and performance
have not been measured.

Ten desktop unit tests and 27 Android JVM tests pass in both debug and release.
The focused decoder test also passes. The signed Android APK is versionCode 73,
versionName 1.0.73-ultra.1, and includes arm64 and x86_64 for phone/emulator
testing.

## Artifacts

- Android: `nullspace-1.0.73-ultra.1.apk`
- Desktop: `nullspace-1.1.8-1-x86_64.pkg.tar.zst`
- Installer: `install-accuracy-experiment.sh` (backs up settings; not run
  automatically)
- Personal dictionary: installed as
  `/usr/share/doc/nullspace/experiment-dictionary.json`

These artifacts are published only as GitHub **ultra prereleases**:
`v1.0.73-ultra.1` (Android), `desktop-v1.1.8-ultra.0` (desktop), and Squelch
`v0.14.0-ultra.0` (the desktop dictation engine). They are not installed
automatically, and no main/stable promotion or AUR update is implied.
