# Flowtype

Wispr Flow–style dictation for Android. A floating mic button sits above
whatever keyboard you use: tap it, speak, tap again, and cleaned-up, punctuated
text appears at your cursor in any app.

- Transcription runs **on the phone** (Parakeet, through sherpa-onnx). Audio
  never leaves it.
- **AI cleanup** with OpenAI's Luna, using your own API key entered in the app.
  It removes fillers, resolves self-corrections and fixes punctuation. Optional:
  without a key you get the phone's own punctuated text.
- A **personal dictionary** for names, jargon and replacements.

Built for the Samsung Galaxy S25 first. Status: **Phase 0**, the first slice
of a new app: the button, on-phone transcription and insertion, plus developer
screens that measure speed and insertion. See [docs/PLAN.md](docs/PLAN.md) for
the whole plan and [PRIVACY.md](PRIVACY.md) for what leaves the phone.

## Build

Builds run on the build box, never on the laptop (see [AGENTS.md](AGENTS.md)):

```bash
scripts/remote-build.sh setup     # once: JDK 17 + Android SDK on the box
scripts/remote-build.sh apk       # debug APK → out/flowtype-debug.apk
scripts/remote-build.sh test      # unit tests
scripts/remote-build.sh install   # adb install the APK onto the phone
```

## Origin

This repository started from [Phone Whisper](https://github.com/kafkasl/phone-whisper)
by Pol Alvarez (Apache-2.0), and its history is kept. Flowtype is a new app
written from scratch; see [NOTICE](NOTICE).
