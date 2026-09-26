# Phase 0 results

Galaxy S25 (SM-S931U), Android 16, One UI 8.5, sherpa-onnx 1.13.8, 4 threads.
Measured 2026-09-25. Ethan chose to finish Phase 0 without the 10 personal
recordings, so there are no accuracy numbers on his own voice (see "Not
measured").

## Speech models (bench on the model's own sample audio, repeated to length)

| Model | Load | Decode 5 s | 15 s | 60 s | RSS after load | RSS after 60 s decode |
|---|---|---|---|---|---|---|
| Parakeet v2 (0.6B) | 1.9 s | 181 ms | 535 ms | 3.0 s | +663 MB | 1.66 GB (process, after the 110M run) |
| Parakeet 110M | 1.07 s | 107 ms | 307 ms | 1.13 s | +209 MB | 1.23 GB |
| Parakeet unified | not run | | | | | |

Real dictations with v2 in the service (Messages): 3.5 s of speech decoded in
210 ms, 6.6 s in 346 ms, model already loaded. Stop → text in the field is
therefore about 0.25–0.4 s for everyday lengths without cleanup, inside the
0.8 s offline target.

Whole-utterance decoding of 60 s pushes the process past 1.2–1.7 GB. Live
chunking (Phase 2) is needed for long dictations for memory as well as speed.

## First end-to-end dictations (2026-09-26, Phase 2 build)

Two ~3.5 s dictations in Flowtype's own field, v2 with live chunking, Luna at
the default tier, style "general":

| | 1st (cold) | 2nd (warm) |
|---|---|---|
| Decoded while talking / left after ✓ | 0 / 149 ms | 141 / 1 ms |
| Luna first token / total | 1147 / 1258 ms | 790 / 1070 ms |
| Prompt cache | 0 of 1303 tokens | 1278 of 1304 tokens |
| Stop → text in field | 1453 ms | 1099 ms |

So the ≥ 1,024-token prefix is cached as planned, and a warm dictation lands
inside the 1.2 s target. Luna's first token is the bulk of it. Still to run:
the Developer tools → Cleanup timing comparison (Luna fast, gpt-4.1-nano).

## Insertion

| Where | Path | Result |
|---|---|---|
| Flowtype's own single-line field | commitText + getSurroundingText | VERIFIED, check 8–16 ms, no retries |
| Google Messages | commitText + getSurroundingText | VERIFIED (2 real dictations), check 13–17 ms |
| Other §1 apps | | not run yet (the matrix needs the phone and a helper) |

## Platform

| Question | Answer |
|---|---|
| Does an `adb install` avoid the restricted-settings block? | Yes on Android 16 / One UI 8.5: the install is recorded as source "other" (`packageSource=1`) and the service turned on with no extra step |
| Mic from the background, no foreground service | Works: dictations into Messages had real signal (peak 0.08–0.16, under 2% zero samples) |
| Field watcher (`flagInputMethodEditor`) | `onStartInput` fires for every focused field, including non-text ones (`type=0x0`); the keyboard-window check keeps the button hidden there |
| sherpa-onnx AAR | Carries the Kotlin API; no vendoring. Runs fine with the app's Kotlin 2.0 |
| Service after an app update | Reconnects by itself |

## Decisions

| Decision | Recommendation | Basis |
|---|---|---|
| Default ASR model | **Parakeet v2** | Ethan's call without recordings; it "works ridiculously well" in use, and 180 ms for 5 s is fast enough. Unified stays downloadable but untested |
| Model loading | **Keep: load when a text field takes input**, hold while in use, release after 15 minutes idle | Load is 1.9 s, far above the 0.5 s bar for load-on-tap |
| Cleanup model and tier | **gpt-6-luna, default tier, 1.8 s deadline (provisional)** | Two real dictations: 1.07–1.26 s total, cache hits from the second call. The fast-tier and nano comparison hasn't run yet |
| Any app needing the paste path | **None so far** (2 of 9 apps) | Phase 1 builds SET_TEXT and paste anyway, per §4.6 |
| Foreground service for the mic | **Not needed** | Background capture works |

## Not measured

- Accuracy on Ethan's voice, chunked vs whole, and the unified A/B: skipped
  with the recordings (Ethan, 2026-09-25). The bench is ready if needed.
- Cleanup timing for Luna fast and gpt-4.1-nano (Luna's default tier is
  measured above).
- The rest of the app matrix (Gmail, WhatsApp, Chrome, Slack, Discord, Keep,
  Samsung Notes, ChatGPT/Claude) and Chrome/WebView pages.
- Battery over 10 minutes of use; the unified model's speed and RAM; v2 RAM in
  a fresh process.
- The button with Samsung Keyboard (the phone uses Gboard) and after One UI's
  sleeping-apps management.
