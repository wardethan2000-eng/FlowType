# Flowtype privacy

Flowtype is a dictation app for Android. It has no server and no accounts, and
collects nothing.

- **Your voice stays on your phone.** Speech is transcribed on the phone. Audio
  is kept in memory only until it has been transcribed, and is never uploaded.
- **AI cleanup is optional.** If you add your own OpenAI API key, the transcribed
  text (never audio) is sent from your phone to OpenAI to tidy it up, with
  storage turned off (`store: false`). Without a key, nothing is sent anywhere.
- **Your key** is encrypted with a key held in the Android Keystore, stays in
  the app's private storage, is left out of backups, and is only ever sent to
  `api.openai.com`.
- **The accessibility service** is used to show the mic button while you type
  and to insert your dictation at the cursor. Flowtype doesn't read, store or log
  the text in your fields, and it never runs in password fields.
- **History**: your last 300 dictations are kept on the phone, in the app's
  private storage, for 7 days by default (1 or 30 days, or off, in History).
  They're never uploaded, backed up or logged, and "Delete all" removes them.
- **Logs** hold timings, lengths and app names only, never what you said.
- Speech models are downloaded from sherpa-onnx's releases on GitHub.

Questions: open an issue at https://github.com/wardethan2000-eng/FlowType.
