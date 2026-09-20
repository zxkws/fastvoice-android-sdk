# FastVoice Android SDK agent instructions

## Knowledge

- Current implementation facts come from source, Gradle configuration, tests, and `CHANGELOG.md`.
- Long-term FastVoice knowledge lives in `/Users/q/note/valt/Workspace/FastVoice/`.
- Android SDK internal guide: `/Users/q/note/valt/Workspace/FastVoice/android-sdk/guide.md`.
- Cross-server/SDK architecture and current business decisions start from `/Users/q/note/valt/Workspace/FastVoice/FastVoice 语音助手.md`.
- `README.md` and `CHANGELOG.md` stay in this repository because they are public integration/release material.
- Do not recreate `.workbuddy/memory` or a second long-term docs tree in this repository.

## Development

- Use JDK 17 for Gradle verification.
- Keep the SDK generic: Android owns connection/audio/playback/control/location transport; ASR/TTS/LLM/MaxKB remain server concerns.
- Do not add backward-compatibility layers unless explicitly required. `1.0.0` is the current breaking baseline.
- Public API or wire compatibility changes must update `README.md`, `CHANGELOG.md`, and the relevant Obsidian FastVoice knowledge.

## Verification

For SDK-affecting changes, at minimum run:

```bash
./gradlew :fastvoice-sdk:testDebugUnitTest :fastvoice-sdk:lintDebug :sample:assembleDebug
```

Review the full diff before completion and preserve release-bound files in the repository.
