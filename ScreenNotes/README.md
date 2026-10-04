# Screen Notes: beginner guide

An Android app that watches your screen during a lecture or video, saves a screenshot each time
the slide changes and settles, reads the text on it, and turns everything into clean notes with Gemini AI.

## What you need (all free)
1. A Windows / Mac / Linux computer (needed once, to build the app)
2. Android Studio: https://developer.android.com/studio
3. A free Gemini API key: https://aistudio.google.com/apikey
4. Your Android tablet/phone + USB cable

## Build and install, step by step
1. Install Android Studio (accept all defaults; it downloads the Android SDK).
2. Open Android Studio, choose **Open**, and select this `ScreenNotes` folder.
3. Wait for "Gradle sync" to finish (first time takes several minutes). If it asks to
   install missing SDK components, click the links to accept.
4. On your tablet: Settings > About tablet > tap **Build number** 7 times, then
   Settings > Developer options > turn on **USB debugging**.
5. Plug the tablet in with USB, tap "Allow" on the tablet.
6. In Android Studio, pick your tablet in the device dropdown at the top, then click the green **Run** button.
7. The app installs and opens.

## Using it
1. Paste your Gemini API key on the home screen (saved on your device).
2. Tap **Start capturing** and accept Android's screen-recording prompt.
3. Open your lecture / video. Leave it running.
4. When done, pull down the notification and tap **Stop**, or reopen the app and tap Stop.
5. Open the session from History > see screenshots + extracted text > **Make AI notes** > edit > Save / Share.
6. Delete a whole session or a single screenshot with the Delete buttons.

## How capture works
It looks at the screen twice a second. When the screen settles it compares with the last saved screenshot:
- nothing new: ignored
- same content plus more (teacher keeps writing): the old screenshot is REPLACED by the fuller one
- old content changed: the old one is kept and a NEW screenshot is saved

## Tuning (top of CaptureService.kt)
- Saves too many near-duplicates? Raise LOST_LIMIT (0.06 to 0.12) or MIN_ADDED (2 to 4).
- Misses small additions? Lower CONTENT_T (6 to 4) or MIN_ADDED (2 to 1).
- Misses very fast slides? Lower TICK_MS (500 to 350) and SETTLE_TICKS (2 to 1).

## Roadmap after this works
- Phase 2: short animated clips (GIF) when a slide animates
- Phase 3: audio transcription (Whisper) merged with slides by timestamp
- Phase 4: send the screenshots themselves to Gemini (diagrams/handwriting), PDF/Word export, search across history
