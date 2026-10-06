# Screen Notes: guide

An Android app that watches your screen during a lecture or video. Each time the slide changes
and settles, it saves a screenshot and reads the text on it. It turns the captured screenshots and
OCR into clean study notes using a 24-model, multi-provider AI ladder.

## What you need (all free)
1. Your Android tablet/phone.
2. Any AI provider key you want to use (optional). Pollinations is a keyless fallback; Gemini,
   OpenRouter, Groq, Cerebras, NVIDIA, HuggingFace, Mistral, and GitHub Models keys are entered
   and kept only on your device.
3. This GitHub repository. GitHub builds the app for you, so **no computer or Android Studio is needed**.

## Install
1. On your tablet, open this repository's **Releases** page in the browser. If the repository is
   private, log in to GitHub first.
2. Under the release marked **Latest**, tap **ScreenNotes.apk**.
3. Open the downloaded file. If Android asks, allow your browser to install apps, then tap **Install**.

After that, update from inside the app: **Updates** tab → **Install latest version**. Updates
install over the old app and keep all your notes.

## Using it
1. On the Notes tab, expand **AI sources** and paste any provider keys you have. The existing
   Gemini key is kept automatically; Pollinations works without a key. Tap **Test** beside a key
   to check it. Top tier becomes the default when at least two provider families are ready.
2. Choose **Top tier when possible** for two independent AI drafts plus an evidence-checked merge,
   or **Fast (one AI)** for one response with automatic provider fallback.
3. Tap **Start capturing** and accept Android's screen-recording prompt.
4. Open your lecture / video. Leave it running.
5. When done, pull down the notification and tap **Stop**, or reopen the app and tap Stop.
6. Open the session from History to see the screenshots and extracted text. Tap **Make AI notes**,
   edit, then Save / Share. Gemini receives screenshots; the other providers receive OCR text.
7. Use **Save as PDF** to write directly to `Downloads/ScreenNotes/` and open the saved file.
   Export is unavailable until the notes contain text.
8. Delete a whole session or a single screenshot with the Delete buttons.

The source card covers 24 models across Gemini (2), OpenRouter (5), Groq (3), Cerebras (2),
NVIDIA (2), HuggingFace (3), Mistral (2), GitHub Models (2), and keyless Pollinations (3).
Store one key per keyed provider family; that one key is shared by its listed models.

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
It looks at the screen four times a second. While the screen is moving (a video at 2x speed, a fast
scroll) it keeps only the SHARPEST frame of that movement, and when the screen settles - or when the
settled screen turns out to be a different slide - it saves that sharpest frame instead of the last
half-drawn one. Slides that are on screen for only about a second are still caught, and at most one
screenshot is written every 1.2 seconds, so fast scrolling cannot flood your storage.

Every frame is cleaned before it is saved: the status bar, the navigation bar, uniform dark (or
uniform light) borders on all four sides (letterbox bars, player sidebars) and a translucent
player-controls band over the bottom edge are cropped away - never content.

When the screen settles, it compares it with the last saved screenshot:
- Nothing new: ignored.
- Same content plus more (the teacher keeps writing): the old screenshot is REPLACED by the fuller one.
- Old content changed: the old one is kept and a NEW screenshot is saved.

After the notes are written, one final AI pass judges them against the captures: cut sentences,
numbered steps and diagrams split over two captures are rebuilt from the captures that hold the
missing half, and anything that is in no capture at all is marked
`[gap: not visible in captures]` instead of being invented. The note shows a one-line notice when
such a gap was marked.

## Tuning (top of CaptureService.kt)
You can ask for these in the Updates tab, or change them yourself:
- Saves too many near-duplicates? Raise LOST_LIMIT (0.06 to 0.12) or MIN_ADDED (2 to 4).
- Misses small additions? Lower CONTENT_T (6 to 4) or MIN_ADDED (2 to 1).
- Misses very fast slides? Lower SETTLE_TICKS (2 to 1), MIN_SAVE_GAP_MS (1200 to 800) or
  MAX_EPISODE_MS (2000 to 1200). TICK_MS is already 250 (four looks a second).
- Saves too many screenshots while scrolling? Raise MIN_SAVE_GAP_MS (1200 to 2000).
- Crops too much (or too little) of a player's edges? MAX_V_SIDE / MAX_H_SIDE limit how much of the
  height/width a border may take, and OVERLAY_MAX limits the player-controls band (0 to switch it off).

## Roadmap
- Phase 2: short animated clips (GIF) when a slide animates
- Phase 3: audio transcription (Whisper) merged with slides by timestamp
- Phase 4: send the screenshots themselves to Gemini (diagrams/handwriting), PDF/Word export,
  search across history

## Building on a computer (optional)
Open this `ScreenNotes` folder in Android Studio (https://developer.android.com/studio), wait for
Gradle sync, connect your device with USB debugging on, and click **Run**. The app is signed with
the included `app/debug.keystore`, so it installs over the GitHub builds and the other way round.
