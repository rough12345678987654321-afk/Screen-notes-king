# Screen Notes: notes for the AI developer

The AI updater (`.github/workflows/ai-update.yml`) reads this file before every change.
Humans are welcome too.

## What this app is

A **personal** Android app used by one student for **JEE preparation** (Physics, Chemistry,
Maths). It isn't published on any store: the owner installs it on their phone/tablet straight
from this GitHub repository.

While the owner watches an online lecture or video, the app captures the screen. Each time the
slide or board changes and settles, it saves a screenshot and reads its text on the device. Then
it turns the screenshots into clean study notes with Gemini.

The owner requests changes from the app's **Updates** tab and isn't a programmer.

**The app is used every day for studying, so a reliable app matters more than fancy features.**
Keep changes small, clear and safe.

## Code map

Everything is in `ScreenNotes/app/src/main/java/com/example/screennotes/`:

| File | What it does |
|---|---|
| `MainActivity.kt` | App start, bottom tabs (Notes / Updates), notes list (`Home`) and a note (`Detail`) |
| `CaptureService.kt` | Screen capture (MediaProjection foreground service): slide-change detection, OCR, saving screenshots. Tuning constants at the top |
| `Gemini.kt` | Makes the AI notes (Gemini API with HttpURLConnection + org.json). The notes prompt lives here |
| `Db.kt` | Room database: `Note` and `Shot` (screenshots) |
| `UpdatesUi.kt` | Updates tab screens |
| `UpdateRepo.kt` | Updates tab actions and settings |
| `UpdateModels.kt` | Reads the AI's status comments |
| `UpdateWorker.kt` | Background checks, notifications, APK install |
| `GitHub.kt` | Small GitHub API client |
| `Markdown.kt` | Shows Markdown text |

Other files:
- `ScreenNotes/app/build.gradle.kts`: dependencies and build config.
- `ScreenNotes/app/src/main/AndroidManifest.xml`: permissions and components.
- UI: Jetpack Compose + Material 3 with the default `MaterialTheme`, plain `Column`/`LazyColumn` layouts, and no navigation library. Screens switch with simple state variables.

## Build

- `cd ScreenNotes && gradle assembleDebug`. There's no Gradle wrapper; CI uses Gradle 8.9 + JDK 17.
- Versions: AGP 8.5.2, Kotlin 2.0.20 (+ Compose compiler plugin), KSP 2.0.20-1.0.25,
  compileSdk 34, minSdk 26, targetSdk 34, Compose BOM 2024.09.00.
- Only add libraries when really needed, and pick versions compatible with the above. Prefer
  what's already there: coroutines, Room, Coil, ML Kit, org.json, HttpURLConnection, WorkManager.
- Avoid experimental Compose APIs (anything needing `@OptIn(ExperimentalMaterial3Api::class)`):
  `TopAppBar`, `ModalBottomSheet`, etc.

## Rules

1. **Never lose the owner's notes.** If you change a Room `@Entity`, increase the database
   `version` and add a `Migration`. Never use `fallbackToDestructiveMigration`.
2. **Updates must install over the existing app.** Never change `applicationId`, `versionCode`,
   the signing config or `app/debug.keystore`.
3. **Don't break the Updates tab or the Notes capture flow**, unless the request is about them.
   The Updates tab is how the owner gets every future change.
4. Notes are shown as plain text, so formulas are written in plain text with Unicode
   (H₂O, x², √, →, ⇌, ΔH, α, β), not LaTeX.
5. Keep the UI simple and readable on a phone. Use the existing styles (`MaterialTheme.typography`, 16.dp padding).
6. The Gemini API key is entered by the owner in the app and stored on the device
   (`SharedPreferences "p"`, key `"key"`). Never hard-code keys or tokens.
7. Don't touch `.github/`, `.ai/` or `AGENTS.md`; the updater can't change them anyway.
