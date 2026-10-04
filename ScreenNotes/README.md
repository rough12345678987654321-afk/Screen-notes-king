# Screen Notes: guide

An Android app that watches your screen during a lecture or video. Each time the slide changes
and settles, it saves a screenshot and reads the text on it. Then it turns everything into clean
notes with Gemini AI.

## What you need (all free)
1. Your Android tablet/phone.
2. A free Gemini API key for the notes: https://aistudio.google.com/apikey
3. This GitHub repository. GitHub builds the app for you, so **no computer or Android Studio is needed**.

## Install
1. On your tablet, open this repository's **Releases** page in the browser. If the repository is
   private, log in to GitHub first.
2. Under the release marked **Latest**, tap **ScreenNotes.apk**.
3. Open the downloaded file. If Android asks, allow your browser to install apps, then tap **Install**.

After that, update from inside the app: **Updates** tab → **Install latest version**. Updates
install over the old app and keep all your notes.

## Using it
1. Paste your Gemini API key on the Notes tab (it's saved on your device).
2. Tap **Start capturing** and accept Android's screen-recording prompt.
3. Open your lecture / video. Leave it running.
4. When done, pull down the notification and tap **Stop**, or reopen the app and tap Stop.
5. Open the session from History to see the screenshots and extracted text. Tap
   **Make AI notes**, edit, then Save / Share.
6. Delete a whole session or a single screenshot with the Delete buttons.

## Changing the app: the Updates tab
You don't need to code. Describe what you want and the AI does it on GitHub:

1. **Updates → New update request**: type the change, attach screenshots if they help, tap **Send to AI**.
2. Watch it work live: reading your request → changing the code → building the app. You get a
   notification when it's done or when it has a question.
3. Tap **Install test version** and try it. Want something different? Write a reply and the AI changes it.
4. Happy? Tap **Approve & merge**. A few minutes later **Install latest version** appears.

Nothing reaches your main app until you approve. One-time setup is explained in the Updates tab
and in the [main README](../README.md).

Ideas to try: "save fewer near-duplicate screenshots", "add a search box for my notes", "export
notes as PDF", "make the notes prompt focus on JEE formulas and solved examples".

## How capture works
It looks at the screen twice a second. When the screen settles, it compares it with the last
saved screenshot:
- Nothing new: ignored.
- Same content plus more (the teacher keeps writing): the old screenshot is REPLACED by the fuller one.
- Old content changed: the old one is kept and a NEW screenshot is saved.

## Tuning (top of CaptureService.kt)
You can ask for these in the Updates tab, or change them yourself:
- Saves too many near-duplicates? Raise LOST_LIMIT (0.06 to 0.12) or MIN_ADDED (2 to 4).
- Misses small additions? Lower CONTENT_T (6 to 4) or MIN_ADDED (2 to 1).
- Misses very fast slides? Lower TICK_MS (500 to 350) and SETTLE_TICKS (2 to 1).

## Roadmap
- Phase 2: short animated clips (GIF) when a slide animates
- Phase 3: audio transcription (Whisper) merged with slides by timestamp
- Phase 4: send the screenshots themselves to Gemini (diagrams/handwriting), PDF/Word export,
  search across history

## Building on a computer (optional)
Open this `ScreenNotes` folder in Android Studio (https://developer.android.com/studio), wait for
Gradle sync, connect your device with USB debugging on, and click **Run**. The app is signed with
the included `app/debug.keystore`, so it installs over the GitHub builds and the other way round.
