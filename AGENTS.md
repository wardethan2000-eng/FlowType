# AGENTS.md — How to work in this repo

Read this before writing code. The plan is [docs/PLAN.md](docs/PLAN.md): build
what it says, in its phase order, and update it when a decision changes.

## Layout

```text
app/src/main/kotlin/com/kafkasl/phonewhisper/   the app (package renamed in Phase 1)
app/src/main/kotlin/com/k2fsa/sherpa/onnx/      vendored sherpa-onnx bindings, v1.12.28
app/src/test/                                   JVM unit tests
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
- sherpa-onnx's native libraries and its Kotlin bindings move together: bump
  `VERSION` in `scripts/fetch-sherpa-onnx.sh` and replace the bindings in one
  commit.

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
