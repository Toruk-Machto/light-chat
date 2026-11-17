# Light Chat 0.2.2

- Run retention cleanup only while the tool is visible. Cancel an unfinished model refresh when the app pauses and resume it on return.
- Clear the SDK's screen ViewModel stores when Android destroys the Activity, preventing old cleanup loops from accumulating.
- Stop Gemini model discovery after a repeated page token, 20 pages or two minutes, including pages containing no usable models.
- Release the microphone at the existing 40-second dictation limit. Keep the captured recording available for Finish dictation.
- Serialize chat file access during Activity replacement so overlapping reads cannot interrupt an atomic save. The final draft edit is tested with immediate app closure.
- Cancel the live voice socket immediately when ending the session, including queued audio and heartbeat traffic.

Provider selection, context confirmation, attachments, history, dictation and voice controls remain available. A text reply already requested can finish within its existing bounded request deadline while the app is paused.

## Validation

18 unit tests cover request formatting, models, pagination termination, attachments, context, retention, errors, audio formats and reply formatting. ARM64 Android 14 emulator tests cover repeated Activity creation/destruction, cleanup pause/resume, cancelled model discovery, saved chats and drafts, microphone release, simulated transcription, actual audio playback/pause/resume and simulated live session cleanup. No provider API keys are embedded or required by these tests. Live provider quotas and real remote speech accuracy are outside this battery regression run.

```sh
adb shell am instrument -w sg.lightchat.test/sg.lightchat.BatteryChecks
```

The SDK includes a narrowly scoped lifecycle fix and test-harness support. Instrumentation code is excluded from release APKs. Physical battery savings require comparison on a Light Phone III.
