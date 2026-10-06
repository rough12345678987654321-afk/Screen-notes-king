# Screen Notes — Problems found & upgrade plan

Prepared 6 Oct 2026 on branch `arena/94c1f3aa-screen-notes-king` (from `main` @ `fd720e5`).
Evidence: your 12 screenshots (5 Oct), GitHub issues #2–#16 with the bot's activity logs,
and the code on `main` plus the 7 unmerged `ai/update-*` branches.

---

## 1. The problem list

### A. Quota & money — the root cause of most of the pain
| # | Problem | Evidence |
|---|---|---|
| P1 | **Notes use `gemini-3.8-flash`, which on the free tier allows only ~20 requests/day** (Flash-Lite models get ~500). Auto-generate on open + every "Make AI notes" tap spends one. When it runs out the app silently drops to Flash-Lite → the quality drop you complained about. | Issue logs: "Switching to gemini-3.5-flash-lite (the first model's daily limit is used up)"; free-tier tables, Sep 2026 |
| P2 | **The update-AI in GitHub Actions also starts on Flash** and one update run makes dozens of calls → quota gone mid-run → 429 waits ("rate-limiting us, waiting 52 s"), 503 failures and repeated "Try again". | #12 comment 3 (failed run), activity logs of #2/#8/#10/#13/#16 |
| P3 | **GitHub Actions minutes:** private repo = 2,000 free min/month. No dependency cache → every build re-downloads Gradle+deps; failed builds, 503 retries and superseded runs burn minutes. | `.github/workflows/*.yml` (no `actions/cache`, no `concurrency`) |
| P4 | **Every notes call uploads up to 40 JPEGs at 1280 px, quality 92** (~1,000+ tokens each) — slow, close to the tokens-per-minute cap, and the first thing that would cost real money if billing were ever enabled. | `CaptureService.kt` (JPEG 92), `Gemini.kt` (MAX_IMAGES 40) |
| P5 | **Model-name roulette:** `main` says `gemini-3.8-flash`, the update-16 branch "fixed" things by switching to `gemini-1.5-flash` (a 2024-era model). Different installed test versions behave differently. | `Gemini.kt` on main vs `ai/update-16` |

### B. Notes quality & readability (your #4, #6 and the 12:23 screenshots)
| # | Problem | Evidence |
|---|---|---|
| P6 | **LaTeX leaks into notes** (`$$…$$`, `\frac`, `\theta`) although the prompt forbids it — Flash-Lite ignores the rule even more. | Screenshots 12:23 (×2) |
| P7 | **"[Screenshot 2]"-style references point at nothing** — the actual slide images are not shown in preview or PDF. | Screenshots 12:13, 9:55 |
| P8 | **Three competing notes prompts** exist (main's original, #4's "elite teacher" wall of text, #6's emoji version) — **none is merged**, so the app you use daily has none of the improvements you paid for in test builds. | PRs #5, #7 open |
| P9 | ASCII-art diagrams inside code blocks are hard to read on a tablet. | Screenshot 12:23 (#2) |

### C. Capture pipeline
| # | Problem | Evidence |
|---|---|---|
| P10 | **First "slide" is often junk**: file manager / video-player UI, because capture starts before the lecture does. | Screenshot 1:05 ("Captured screens (14)" starting with the video picker) |
| P11 | **Status bar baked into every screenshot** → OCR noise ("12:13 … 44%") and ugly pages in PDFs. | All capture screenshots |
| P12 | **Black letterbox bars** from video players are kept and counted as content. | Screenshot 1:05; also noticed by the update-AI in #12 |

### D. Export
| # | Problem | Evidence |
|---|---|---|
| P13 | **"Save as PDF" uses Android's print framework → "not supported" on your realme.** | #13 |
| P14 | Exported PDF/HTML has **no slide images** and no topic/date filename. | Screenshot 2:14 |

### E. Workflow — the meta-problem behind "it was working, now it isn't"
| # | Problem | Evidence |
|---|---|---|
| P15 | **7 update pull requests are open and unmerged** (#2, #4, #6, #10, #12, #13, #16). You live on stacked test APKs; "Install latest version" (built from `main`) contains almost none of them; each new request branches from stale `main`, so features disappear ("That drive link thing is not working") and edits to `MainActivity.kt` collide. | `gh pr list`: only #1 and #9 ever merged |
| P16 | Tested features stuck in limbo: date/time in History (#2), PDF & link import (#12), PDF-save fix (#13). | Same |

---

## 2. Upgrades I can make (researched)

**U1 — Backlog rescue (no app code risk, biggest win).** I merge the 7 update branches into
`main` in dependency order, resolve the `MainActivity.kt`/`Gemini.kt` conflicts, drop the bad
`gemini-1.5-flash` rename, keep one canonical notes prompt, and let CI publish **one release
that contains everything you already tested** (date/time, preview tabs, PDF export, auto-notes,
imports). Stale PRs get closed. Afterwards "Install latest version" = the good app.

**U2 — One source of truth for models + in-app model picker.** Valid ladder
`gemini-3.8-flash → gemini-3.5-flash-lite`, chosen in the app's settings, with a visible
one-line notice whenever the fallback fires (no more silent quality drops). CI keeps using the
`GEMINI_MODEL` / `GEMINI_FALLBACK_MODEL` repo variables.

**U3 — Quota & cost engineering (the "money" bundle).**
- *Key pool:* free-tier quota is **per Google project**, so 2–3 free projects = 2–3× the daily
  requests. Notes tab accepts a small list of keys and rotates on 429; CI secret becomes a
  rotating pool the same way. Free, legal, no billing.
- *Cheaper calls:* downscale/compress images to ~1024 px / q80 before upload (≈40–50 % fewer
  tokens), skip re-sending images whose OCR text already says everything.
- *No waste:* never regenerate notes that exist (guard already added by #8 — make it a
  confirmation dialog on manual re-runs).
- *CI minutes:* add `actions/cache` for Gradle, `concurrency: cancel-in-progress` for superseded
  runs, and skip the test-APK rebuild when nothing under `ScreenNotes/` changed. Roughly halves
  minutes per update.

**U4 — Notes quality pass (your #4 + #6, done properly).** One canonical prompt that merges the
useful parts of both requests: JEE-focused structure (concept → why it exists → derivation steps →
formula box in plain Unicode → worked-example style → exam traps → 5-line recap), emoji section
markers, and **a post-processing sanitizer** that rewrites leftover LaTeX (`\frac{a}{b}`, `\theta`,
`\approx`, `$$…$$`) into Unicode (θ, ≈, →, a/b, sub/superscripts) before the notes are saved —
so even a disobedient Flash-Lite can't ship LaTeX.

**U5 — Images inside the notes.** Parse `[Screenshot N]` and inline the real captured slide
(thumbnail, tap = full screen) in the preview and in the PDF. Kills P7 and makes diagrams
redundant (P9).

**U6 — Capture clean-up.** Crop status/nav bars at capture time; skip near-black frames and
frames that match launcher/player chrome; 3-second warm-up after "Start capturing"; auto-crop
letterbox bars. Kills P10–P12 and shrinks every downstream cost.

**U7 — Real PDF export.** Write the PDF directly with Android's `PdfDocument` into
`Downloads/ScreenNotes/<topic>_<date>.pdf` (rendered Markdown + slide images), then offer the
share sheet. No print framework → no "not supported" on realme. Print kept as a fallback.

**U8 — Imports that actually work.** PDF/slide decks imported on-device (`PdfRenderer` + ML Kit
OCR — **zero API quota**); public Drive files by file ID; YouTube/any video = one-tap
"open the video and start capturing" (downloading YouTube is blocked, as the update-AI found
in #12 — capturing it is not).

---

## 3. Recommended order

1. **U1** (unblocks everything else; afterwards one install has all tested features)
2. **U3 + U2** (money & quota: fewer failures, fewer waits, no silent quality drops)
3. **U4 + U5** (the notes themselves: quality, Unicode formulas, images inline)
4. **U6** (cleaner slides, less junk, cheaper calls)
5. **U7** (PDF that always works on your tablet)
6. **U8** (PDF/Drive/YouTube intake)

Everything respects the house rules in `AGENTS.md` (no data loss, same signing/app id,
no experimental Compose APIs, `.github/` touched only for CI caching in U3).

## 4. Sources for the quota facts
- Free-tier daily requests per model (Sep 2026): scriptbyai.com/gemini-api-free-tier-limits
- Free tier now Flash/Flash-Lite only, quotas adjusted without notice: questloops.com (Aug 2026)
- Paid pricing reference (what billing would cost): findskill.ai (May 2026)

---

## 5. Status (6 Oct 2026)

- **U1 done** — PR #18 merged into main: updates #2, #4, #6, #10, #12, #13 are now in one
  release; the 7 stale pull requests were closed as superseded; update #16's bad model
  rename was dropped.
- **U2–U8 done** — PR #19 (branch arena/94c1f3aa): model ladder + picker + honest fallback,
  key-pool rotation, downscaled uploads, Gradle cache, regenerate guard (U2+U3); canonical
  JEE prompt + LaTeX→Unicode sanitizer NotesClean.kt (U4); slide images inline in HTML/PDF +
  numbered zoomable slides (U5); capture warm-up, status-bar/letterbox crop, empty-frame skip
  (U6); direct PDF export to Downloads/ScreenNotes via PdfExport.kt, no print dialog (U7);
  Drive-PDF import by link + video links hand off to capture (U8). CI build green.
- Still open for later: issue #16 (Drive edge cases), closing the finished issues (the Arena
  token cannot close issues; tap them closed on GitHub or in the app), and making the
  repository private (setup card in the Updates tab).
