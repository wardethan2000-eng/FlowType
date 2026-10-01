# AGENTS.md — How to work in this repo

Read this before writing code. The plan is private (2026-10-01): `PLAN.md`
and `LAUNCH.md` in the private repo `wardethan2000-eng/FlowType-private`,
cloned at `private/` here and ignored by this public repo. Clone it there if
it's missing (`gh repo clone wardethan2000-eng/FlowType-private private`).
Build what the plan says, in its order, update it when a decision changes,
and commit and push it in `private/`. "PLAN §n" in comments means that file.
Nothing about pricing, launch or competitors goes in this public repo.

## Layout

```text
app/src/main/kotlin/com/ethanward/flowtype/     the app
  service/   accessibility service: field watcher, record → decode → clean → insert
  overlay/   the mic button, listening panel and waveform
  audio/     mic capture, WAV, loudness stats
  asr/       sherpa-onnx models: catalog, download, decode, VAD segments, WER
  dictionary/ words and replacements, the offline pass, dictionary.json
  insert/    commitText → safe SET_TEXT → paste → copy, insertion log
  cleanup/   prompt, streamed Responses API call, guards, Keystore-held key
  ui/        every screen: home, AI cleanup, dictionary, models, developer tools
app/src/test/                                   JVM unit tests
app/libs/sherpa-onnx.aar                        fetched on the box, never committed
scripts/remote-build.sh                         every build, run on the build box
scripts/builder/                                scripts that run ON the box
private/                                        the plan (private repo, ignored here)
```

## Heavy work runs on the build box, never on Ethan's laptop

Hard rule, the same as in DecalForge: the laptop has 7.4 GB and builds have
crashed it. Gradle, Kotlin compiles and unit tests all go through
`scripts/remote-build.sh` (see its header). It uses the box's one shared queue,
so a Flowtype build waits its turn behind DecalForge jobs rather than competing
with them. If the box is unreachable, say so and ask; don't build locally.

**GitHub Actions** (`.github/workflows/android.yml`) runs the same tests and
APK build on every push to main and on PRs; the repo is public, so its standard
runners cost nothing. `scripts/ci-apk.sh` waits for a pushed commit's run and
downloads its APK. The APK is signed with the box's debug key (the
`DEBUG_KEYSTORE_B64` secret), so it installs over the box's builds. Use the box
for uncommitted work, CI for anything pushed.

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

- [ ] Tests and the APK build are clean: `scripts/remote-build.sh test` / `apk`
      on the box, or the pushed commit's GitHub Actions run.
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
