# Screen Notes

My personal Android app for JEE preparation. It captures lecture screens and turns them into
clean study notes with a 24-model, multi-provider AI engine (including Gemini vision and keyless
fallback). The app's code is in [`ScreenNotes/`](ScreenNotes/), and the
[guide](ScreenNotes/README.md) covers installing and using it.

The app can also **update itself with AI**. Describe a change in the app's **Updates** tab, and
an AI on GitHub writes the code and builds a test version for you to try. It reaches your main
app only after you approve it.

## How an update works
1. **Updates → New update request**: describe the change, add screenshots, tap **Send to AI**.
   The app creates a GitHub issue labelled `app-update`.
2. GitHub Actions starts the AI. Gemini reads the request, the screenshots and the code, then
   edits the code and compiles it. If the build fails, it fixes the errors until it passes.
3. The app shows the progress live and sends a notification when the AI is done or has a question.
4. **Install test version** and try it. Reply in the app for changes, and the AI continues.
5. **Approve & merge**: GitHub builds the final version and publishes it on the Releases page.
   It then shows up in the app as **Install latest version**.

## One-time setup
1. **Make this repository private.** It's a personal app, and otherwise anyone can see your
   requests and screenshots. Go to Settings → General → Danger Zone → Change repository
   visibility → Make private. Everything keeps working.
2. **Gemini key for the AI.** Create a free key at https://aistudio.google.com/apikey in a *new
   project*, so updates never use up the quota your notes need. Add it here as an Actions secret
   named `GEMINI_API_KEY`: Settings → Secrets and variables → Actions → New repository secret.
3. **Connect the app.** Go to the Updates tab → **Create token on GitHub**. Under "Repository
   access" choose *Only select repositories* → this repository, then **Generate token**. Copy the
   token and paste it in the app.
4. *(Optional)* Go to Settings → Actions → General → Workflow permissions and tick **Allow GitHub
   Actions to create and approve pull requests**. Without it, the app opens the pull request
   itself when you approve.

## Limits and costs
- **Gemini:** free keys only allow a few AI runs per day, and limits reset at midnight US
  Pacific time (12:30 pm IST in summer, 1:30 pm IST in winter). When a key hits its daily limit, the AI switches to a
  lighter model automatically. If that's also used up, try again the next day, or turn on billing
  for that key; an update typically costs well under 1 US dollar. You can change the models
  without editing code using the repository variables `GEMINI_MODEL` (default
  `gemini-3.8-flash`) and `GEMINI_FALLBACK_MODEL` (default `gemini-3.5-flash-lite`).
- **GitHub Actions:** unlimited for public repositories. Private ones get 2,000 free minutes a
  month. An AI update takes about 5–15 minutes and an app build about 5, which is enough for
  well over 100 updates a month.

## If something goes wrong
- **The AI failed or got stuck:** open the request and tap **Try again**, or reply with more details.
- **An approved version misbehaves:** the last 3 builds stay on the Releases page. Install an
  older `ScreenNotes.apk` from there, then ask for a fix in the Updates tab.

## What's where
| Path | What it is |
|---|---|
| `ScreenNotes/` | The Android app (Kotlin + Jetpack Compose) |
| `AGENTS.md` | What the AI should know about this app and its rules |
| `.github/workflows/main.yml` | Builds the APK for every change; on `main` it publishes it as the latest release |
| `.github/workflows/ai-update.yml` | Runs the AI for update requests and your replies |
| `.github/scripts/gemini_agent.py` | A small Gemini coding agent. Its only tools read, search and edit files in `ScreenNotes/` and compile the app; it has no shell and no access to secrets |
| `.github/scripts/app_update.py` | Live status for the app, screenshots, work branch `ai/update-<n>`, test APK and pull request |
| `.github/scripts/gradle_errors.py` | Turns Gradle output into short, readable error lists |
