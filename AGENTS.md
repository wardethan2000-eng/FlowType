# AGENTS.md — How to work in this repo

Read this before writing code. The plan is [docs/PLAN.md](docs/PLAN.md): build
what it says, in its phase order, and update it when a decision changes.

## Layout

```text
app/src/main/kotlin/com/ethanward/flowtype/     the app
  service/   accessibility service: field watcher, button, record → decode → insert
  audio/     mic capture, WAV, loudness stats
  asr/       sherpa-onnx models: catalog, download, decode, VAD segments, WER
  insert/    commitText + getSurroundingText check, insertion log
  cleanup/   cleanup prompt, Responses API request/stream, Keystore-held key
  ui/        main screen and the developer screens (bench, insertion, cleanup timing)
app/src/test/                                   JVM unit tests
app/libs/sherpa-onnx.aar                        fetched on the box, never committed
scripts/remote-build.sh                         every build, run on the build box
scripts/builder/                                scripts that run ON the box
docs/PLAN.md                                    the plan
```

## Heavy work runs on the build box, never on Ethan's laptop

Hard rule, the same as in DecalForge: the laptop has 7.4 GB and builds have
crashed it. Gradle, Kotlin compiles and unit tests all go through
`scripts/remote-build.sh` (see its header). It uses the box's one shared queue,
so a Flowtype build waits its turn behind DecalForge jobs rather than competing
with them. If the box is unreachable, say so and ask; don't build locally.

`adb` is the exception: it is light and runs on the laptop, where the phone is.

## Rules that matter here

- **Never destroy text in the user's field.** Any insertion path that can't
  prove where the cursor is must not replace field contents (PLAN §4.6).
- **Never log field text, transcripts or the API key.** Traces carry lengths
  and timings only.
- **The OpenAI key is the user's**, entered in the app and kept in the Android
  Keystore. Never put a key in the code, the repo or a build config.
- **Keep the debug signing key on the box** (`~/.android/debug.keystore`). A
  different key means uninstall, reinstall, and re-granting accessibility.
- sherpa-onnx comes only from its release AAR (native libraries and the
  Kotlin API together): bump `VERSION` and `SHA256` in
  `scripts/fetch-sherpa-onnx.sh` together, from the release's asset digest.
  Never vendor its sources or `.so` files.
- **Recordings stay off the repo** (it's public). The bench reads them from the
  phone (`Android/data/com.ethanward.flowtype/files/recordings/`); a local copy
  goes in `recordings/`, which is ignored.

## Definition of Done

- [ ] `scripts/remote-build.sh apk` and `scripts/remote-build.sh test` are clean.
- [ ] New logic has JVM unit tests (dictionary, guards, prompt building,
      insertion maths especially).
- [ ] Behaviour on screen changed → installed on the S25 and tried in the apps
      it touches. Say which apps, and say so if this was skipped.
- [ ] The diff is small and matches the code around it.

## How to report back

Ethan reads these between other things. Lead with the answer, put what's
broken or unverified before what works, and keep it to one screen. **End every
message with a `Next` list**: what he should do, in order, with any decision
stated plainly. If nothing: `Next: nothing from you — carrying on with X.`
