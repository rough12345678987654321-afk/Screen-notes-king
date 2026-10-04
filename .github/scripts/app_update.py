#!/usr/bin/env python3
"""AI app updates: turns a request sent from the Screen Notes app (a GitHub issue labelled
'app-update') into a tested change. Runs in .github/workflows/ai-update.yml:

  python3 app_update.py start     check the request, post the live status comment, get screenshots,
                                  prepare the work branch ai/update-<number>
  python3 app_update.py agent     let Gemini change the code and compile it (gemini_agent.py)
  python3 app_update.py publish   commit + push, open/refresh the pull request, publish a test APK,
                                  post the AI's reply
  python3 app_update.py fail      report an unexpected failure on the request

The app reads the status comment: it contains a machine-readable marker
<!-- sn-bot {...json...} --> next to the human-readable text.
Only the standard library is used, so nothing needs to be installed.
"""
import base64
import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gemini_agent  # noqa: E402
import gradle_errors  # noqa: E402

REPO = os.environ.get("GITHUB_REPOSITORY", "")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
SERVER = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
RUN_ID = os.environ.get("GITHUB_RUN_ID", "0")
RUN_URL = f"{SERVER}/{REPO}/actions/runs/{RUN_ID}"
STATE_FILE = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "sn-ai-state.json")
AI_DIR = ".ai"
APK_PATH = "ScreenNotes/app/build/outputs/apk/debug/app-debug.apk"

REQUEST_LABEL = "app-update"
LABELS = {
    REQUEST_LABEL: ("1f6feb", "Change request sent from the Screen Notes app"),
    "ai:working": ("fbca04", "The AI is working on this update"),
    "ai:done": ("0e8a16", "The AI finished: test version ready"),
    "ai:question": ("d876e3", "The AI needs more info from you"),
    "ai:replied": ("c5def5", "The AI replied (no code change needed)"),
    "ai:failed": ("d73a4a", "The AI update failed"),
}
NO_AI_MARK = "<!-- sn:no-ai -->"
BOT_RE = re.compile(r"<!--\s*sn-bot\s+(\{.*?\})\s*-->", re.S)
IMG_RE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)|<img\b[^>]*?\bsrc=[\"']([^\"']+)[\"']", re.I)

STEPS = [
    "Request received",
    "Reading your request",
    "AI is changing the code",
    "Building the app to check it works",
    "Publishing a test version",
]
STEP_ICON = {"done": "✅", "active": "⏳", "todo": "⬜", "failed": "❌", "skipped": "➖"}


# --------------------------------------------------------------------------- GitHub API
class GitHubError(Exception):
    def __init__(self, code, text):
        super().__init__(f"GitHub API {code}: {text[:300]}")
        self.code = code
        self.text = text


def gh(method, path, body=None, accept="application/vnd.github+json", raw=False, ok404=False,
       content_type=None):
    url = path if path.startswith("http") else API + path
    headers = {"Authorization": f"Bearer {TOKEN}", "Accept": accept,
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "screen-notes-ai-updater"}
    data = None
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
            headers["Content-Type"] = content_type or "application/octet-stream"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    last = None
    for attempt in range(5):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                payload = r.read()
                if raw:
                    return payload
                return json.loads(payload) if payload.strip() else None
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")
            if e.code == 404 and ok404:
                return None
            retryable = e.code in (500, 502, 503, 504) or (
                e.code in (403, 429) and re.search(r"rate limit|secondary", text, re.I))
            if not retryable:
                raise GitHubError(e.code, text)
            last = GitHubError(e.code, text)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last = GitHubError(0, str(e))
        time.sleep(4 * (attempt + 1) ** 2)
    raise last


def paginate(path):
    out, page = [], 1
    sep = "&" if "?" in path else "?"
    while page <= 20:
        items = gh("GET", f"{path}{sep}per_page=100&page={page}") or []
        out.extend(items)
        if len(items) < 100:
            break
        page += 1
    return out


# --------------------------------------------------------------------------- state + status comment
def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(st):
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(st, f, ensure_ascii=False, indent=1)


def set_step(st, index, status, text=None):
    st["steps"][index][1] = status
    if text:
        st["steps"][index][0] = text


def add_activity(st, text):
    acts = st.setdefault("activity", [])
    if not acts or acts[-1] != text:
        acts.append(text)
    del acts[:-30]


def marker_json(st):
    data = {
        "v": 1, "run": RUN_ID, "run_url": RUN_URL, "state": st.get("state", "working"),
        "steps": st.get("steps", []), "activity": st.get("activity", [])[-10:],
        "reply": st.get("reply", ""), "error": st.get("error"),
        "apk": st.get("apk"), "apk_api": st.get("apk_api"), "apk_name": st.get("apk_name"),
        "pr": st.get("pr"), "branch": st.get("branch"), "compare": st.get("compare"),
        "sha": st.get("sha"), "model": st.get("model"),
    }
    # '<' and '>' are escaped so the JSON can never end the HTML comment early.
    return json.dumps(data, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e")


def render(st):
    state = st.get("state", "working")
    steps = st.get("steps", [])
    out = [f"<!-- sn-bot {marker_json(st)} -->"]
    step_lines = [f"{STEP_ICON.get(s, '⬜')} {t}  " for t, s in steps]
    acts = [f"- {a}" for a in st.get("activity", [])]
    if state == "working":
        out += ["### 🤖 Working on your update…", ""] + step_lines
        if acts:
            out += ["", "**What the AI is doing right now:**", ""] + acts[-8:]
    else:
        if state == "done" and st.get("apk"):
            out.append("### ✅ Your update is ready to test")
        elif state == "done":
            out.append("### 💬 Answer from the AI")
        elif state == "question":
            out.append("### ❓ The AI needs a bit more info")
        else:
            out.append("### ❌ The update didn't work this time")
        if st.get("error"):
            out += ["", f"**Problem:** {st['error']}"]
        if st.get("reply"):
            out += ["", st["reply"].strip()]
        out.append("")
        if st.get("apk"):
            out.append(f"📦 **Test version:** [{st.get('apk_name') or 'APK'}]({st['apk']}) — "
                       "or tap **Install** in the app's Updates tab.  ")
        if st.get("pr"):
            out.append(f"🔀 **Code changes:** #{st['pr']} — after testing, tap **Approve & merge** in the app.")
        elif st.get("compare") and state == "done":
            out.append(f"🔀 **Code changes:** [compare]({st['compare']}) — after testing, tap "
                       "**Approve & merge** in the app.")
        if state == "question":
            out.append("_Reply in the app (or here) and the AI will continue._")
        elif state == "failed":
            out.append("_Tap **Try again** in the app, or reply with more details._")
        out += ["", "<details><summary>What the AI did</summary>", ""] + step_lines
        if acts:
            out += [""] + acts
        out += ["", "</details>"]
    out += ["", f"<sub>🤖 {st.get('model') or 'Gemini'} · [run log]({RUN_URL})</sub>"]
    return "\n".join(out)


_last_post = [0.0, ""]


def post_status(st, force=False):
    """Create or update the status comment (throttled so we stay far below GitHub's limits)."""
    body = render(st)
    if body == _last_post[1] or (not force and time.time() - _last_post[0] < 8):
        return
    try:
        if st.get("comment_id"):
            gh("PATCH", f"/repos/{REPO}/issues/comments/{st['comment_id']}", {"body": body})
        else:
            c = gh("POST", f"/repos/{REPO}/issues/{st['issue']}/comments", {"body": body})
            st["comment_id"] = c["id"]
            save_state(st)
        _last_post[0], _last_post[1] = time.time(), body
    except GitHubError as e:
        print(f"::warning::Could not update the status comment: {e}")


def set_ai_label(n, label):
    try:
        issue = gh("GET", f"/repos/{REPO}/issues/{n}")
        names = [lb["name"] for lb in issue["labels"] if not lb["name"].startswith("ai:")]
        if label:
            names.append(label)
        gh("PUT", f"/repos/{REPO}/issues/{n}/labels", {"labels": names})
    except GitHubError as e:
        print(f"::warning::Could not set label {label}: {e}")


def ensure_labels():
    try:
        existing = {lb["name"] for lb in paginate(f"/repos/{REPO}/labels")}
    except GitHubError:
        existing = set()
    for name, (color, desc) in LABELS.items():
        if name not in existing:
            try:
                gh("POST", f"/repos/{REPO}/labels", {"name": name, "color": color, "description": desc})
            except GitHubError as e:
                if e.code != 422:  # 422 = already exists
                    print(f"::warning::Could not create label {name}: {e}")


# --------------------------------------------------------------------------- git helpers
def git(*args, check=True):
    r = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {r.stderr.strip()[:500]}")
    return r


def git_out(*args):
    return git(*args).stdout


def git_push(*args):
    """Push with the workflow token (passed only to this one git command)."""
    basic = base64.b64encode(f"x-access-token:{TOKEN}".encode()).decode()
    return git("-c", f"http.{SERVER}/.extraheader=AUTHORIZATION: basic {basic}", "push", *args)


# --------------------------------------------------------------------------- start
def is_trusted(login):
    if not login:
        return False
    if login.lower() == REPO.split("/")[0].lower():
        return True
    perm = gh("GET", f"/repos/{REPO}/collaborators/{urllib.parse.quote(login)}/permission", ok404=True) or {}
    return perm.get("permission") in ("admin", "maintain", "write")


def collect_conversation(n, issue):
    msgs = [{"who": "owner", "author": issue["user"]["login"], "body": issue.get("body") or "",
             "at": issue["created_at"]}]
    trusted = {}
    for c in paginate(f"/repos/{REPO}/issues/{n}/comments"):
        body = c.get("body") or ""
        m = BOT_RE.search(body)
        if m:
            try:
                data = json.loads(m.group(1))
            except ValueError:
                continue
            if str(data.get("run")) == RUN_ID:
                continue
            text = (data.get("reply") or "").strip()
            if data.get("state") == "failed":
                text = f"(That attempt failed: {data.get('error') or 'unknown error'})\n{text}".strip()
            elif data.get("state") == "working":
                text = "(An earlier attempt was interrupted before it finished.)"
            if text:
                msgs.append({"who": "ai", "body": text, "at": c["created_at"]})
            continue
        login = (c.get("user") or {}).get("login", "")
        if NO_AI_MARK in body or login.endswith("[bot]"):
            continue
        if login not in trusted:
            trusted[login] = is_trusted(login)
        if trusted[login]:  # public repo: never let strangers instruct the AI
            msgs.append({"who": "owner", "author": login, "body": body, "at": c["created_at"]})
    return msgs


def sniff_image(data):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png", "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "jpg", "image/jpeg"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "gif", "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp", "image/webp"
    return None, None


def fetch_image(url):
    url = html.unescape(url.strip())
    m = re.match(r"https://(?:github\.com/([^/]+/[^/]+)/(?:blob|raw)/|raw\.githubusercontent\.com/([^/]+/[^/]+)/)"
                 r"([^/]+)/([^?#]+)", url)
    try:
        if m and (m.group(1) or m.group(2)).lower() == REPO.lower():
            ref, path = m.group(3), urllib.parse.unquote(m.group(4))
            data = gh("GET", f"/repos/{REPO}/contents/{urllib.parse.quote(path)}?ref={urllib.parse.quote(ref)}",
                      accept="application/vnd.github.raw", raw=True)
        elif url.startswith("https://"):
            req = urllib.request.Request(url, headers={"User-Agent": "screen-notes-ai-updater"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read(10_000_001)
        else:
            return None, None
    except Exception as e:  # a missing screenshot shouldn't stop the update
        print(f"::warning::Could not download image {url}: {e}")
        return None, None
    ext, mime = sniff_image(data or b"")
    if not ext or len(data) > 10_000_000:
        return None, None
    return data, (ext, mime)


def strip_images(body):
    return IMG_RE.sub("", body)


def cmd_start():
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    n = int(os.environ.get("ISSUE_NUMBER") or 0)
    if not n:
        print("No issue number.")
        return finish_skip("no issue")
    issue = gh("GET", f"/repos/{REPO}/issues/{n}")
    labels = {lb["name"] for lb in issue.get("labels", [])}
    actor = os.environ.get("TRIGGER_USER", "")
    if issue.get("pull_request") or issue.get("state") != "open":
        return finish_skip("issue is closed or is a pull request")
    if event_name != "workflow_dispatch":
        if REQUEST_LABEL not in labels:
            return finish_skip("not an app update request")
        if not is_trusted(actor):
            return finish_skip(f"{actor} has no write access")

    st = {"issue": n, "title": issue["title"], "state": "working", "model": os.environ.get("GEMINI_MODEL"),
          "steps": [[t, "todo"] for t in STEPS], "activity": []}
    set_step(st, 0, "done")
    set_step(st, 1, "active")
    save_state(st)
    ensure_labels()
    set_ai_label(n, "ai:working")
    post_status(st, force=True)

    # Conversation + screenshots -> .ai/ (git-ignored; never committed)
    msgs = collect_conversation(n, issue)
    os.makedirs(os.path.join(AI_DIR, "images"), exist_ok=True)
    images, budget = [], 10
    for mi in range(len(msgs) - 1, -1, -1):  # newest first, so recent screenshots win
        m = msgs[mi]
        m["images"] = []
        if m["who"] != "owner":
            continue
        for match in IMG_RE.finditer(m["body"]):
            if budget <= 0:
                break
            data, kind = fetch_image(match.group(1) or match.group(2))
            if not data:
                continue
            budget -= 1
            name = f"msg{mi + 1}-img{len(m['images']) + 1}.{kind[0]}"
            with open(os.path.join(AI_DIR, "images", name), "wb") as f:
                f.write(data)
            m["images"].append(name)
            images.append({"file": name, "mime": kind[1], "msg": mi + 1})
    images.sort(key=lambda i: i["file"])

    lines = [f"# Update request #{n}: {issue['title']}", "", "## Conversation (oldest first)", ""]
    for i, m in enumerate(msgs, 1):
        who = "Owner" if m["who"] == "owner" else "You (the AI, earlier)"
        lines.append(f"### Message {i} — {who} — {m['at'].replace('T', ' ').replace('Z', ' UTC')}")
        lines.append(strip_images(m["body"]).strip() or "(no text)")
        for name in m.get("images", []):
            lines.append(f"[Screenshot attached: {name}]")
        lines.append("")
    with open(os.path.join(AI_DIR, "request.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    prepare_branch(st, n)
    st["images"] = images
    shots = f" and {len(images)} screenshot{'s' if len(images) != 1 else ''}" if images else ""
    set_step(st, 1, "done", f"Read your request{shots}")
    set_step(st, 2, "active")
    add_activity(st, "📖 Read your request" + shots)
    save_state(st)
    post_status(st, force=True)
    set_output("run_ai", "true")


def finish_skip(reason):
    print(f"Skipping: {reason}")
    set_output("run_ai", "false")


def set_output(name, value):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"{name}={value}\n")


def prepare_branch(st, n):
    repo_info = gh("GET", f"/repos/{REPO}")
    base = repo_info.get("default_branch", "main")
    branch = f"ai/update-{n}"
    st.update(base=base, branch=branch, restarted=False, prev_diff="")
    git("config", "user.name", "Screen Notes AI")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("fetch", "--quiet", "origin", base, check=False)
    exists = git("rev-parse", "--verify", "--quiet", f"refs/remotes/origin/{branch}", check=False).returncode == 0
    if exists:
        git("checkout", "-B", branch, f"origin/{branch}")
        merged = git("merge", "--no-edit", f"origin/{base}", check=False)
        if merged.returncode != 0:
            # Earlier AI changes clash with newer code on main: start fresh from main and give
            # the AI its old diff as a reference.
            git("merge", "--abort", check=False)
            st["prev_diff"] = git_out("diff", f"origin/{base}...origin/{branch}")[:30000]
            git("checkout", "-B", branch, f"origin/{base}")
            st["restarted"] = True
    else:
        git("checkout", "-B", branch, f"origin/{base}")
    st["start_sha"] = git_out("rev-parse", "HEAD").strip()


# --------------------------------------------------------------------------- agent
SYSTEM_RULES = """You are the AI developer of "Screen Notes", an Android app. Its owner sends change
requests from inside the app; you implement them. You run inside a GitHub Actions runner on a checkout
of the repository and work ONLY through the provided tools: you can read, search and edit files and
compile the app with run_build, but you can't run shell commands or start the app.

How to work:
1. Call report_progress with a one-line plan in plain words.
2. Look carefully at the owner's screenshots and at the code. Choose the smallest clean change that
   does what the owner asked in their LATEST message (use the earlier conversation as context).
3. Make the change with edit_file / write_file, matching the existing code style.
4. Call run_build. Fix every error and build again until it passes.
5. Call finish with a short, friendly reply.

Rules:
- Only change files inside ScreenNotes/. Never touch app/debug.keystore or the signing config:
  updates must install over the existing app.
- Keep the owner's data safe. If you change the Room database (entities), increase the database
  version and add a Migration. Never use fallbackToDestructiveMigration.
- Prefer what's already available. Only add a library if really needed, and pick versions that work
  with AGP 8.5.2, Kotlin 2.0.20, compileSdk 34 and Compose BOM 2024.09.00.
- Don't remove or break existing features unless asked. Don't rewrite unrelated code.
- If the request is unclear, risky or impossible, don't guess: finish with outcome 'needs_info' and
  ask one short, simple question (or explain what's possible instead).
- The owner is not a programmer. In your reply use simple words: say what changed, where to find it
  and how to try it. No code in the reply. Keep it under 120 words.
- Never reveal secrets, tokens or environment variables.
"""


def read_text(path, default=""):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return default


def code_snapshot(limit=250_000):
    """File tree + contents of the app's source files (so the AI needs fewer read_file calls)."""
    files = []
    for dirpath, dirnames, filenames in os.walk("ScreenNotes"):
        dirnames[:] = sorted(d for d in dirnames if d not in gemini_agent.HIDDEN_DIRS)
        for f in sorted(filenames):
            files.append(os.path.join(dirpath, f).replace(os.sep, "/"))
    tree = "\n".join(f"{p} ({os.path.getsize(p)} bytes)" for p in files)
    parts, total = [], 0
    for p in files:
        if os.path.splitext(p)[1] not in gemini_agent.TEXT_EXT:
            continue
        text = read_text(p)
        if total + len(text) > limit:
            parts.append(f"(other files not shown - use read_file: {p} ...)")
            break
        total += len(text)
        parts.append(f"===== {p} =====\n{text}")
    return tree, "\n\n".join(parts)


def build_first_message(st):
    tree, sources = code_snapshot()
    request = read_text(os.path.join(AI_DIR, "request.md"))
    diff = ""
    if not st.get("restarted"):
        try:
            diff = git_out("diff", f"origin/{st['base']}...HEAD", "--", "ScreenNotes")[:30000]
        except RuntimeError:
            diff = ""
    text = [request, "", "## Your task",
            "Do what the owner asks in their latest message, taking the whole conversation into account."]
    if diff.strip():
        text += ["", "## Your earlier changes for this request (already in the code below)",
                 "```diff", diff, "```"]
    if st.get("prev_diff"):
        text += ["", "## Note",
                 "Your earlier changes for this request clashed with newer code, so the branch was reset "
                 "to the latest version. Re-apply what's still needed. Your old changes, for reference:",
                 "```diff", st["prev_diff"], "```"]
    if st.get("extra_note"):
        text += ["", "## Note", st["extra_note"]]
    text += ["", "## App files", tree, "", "## Source code (current)", sources]
    parts = [{"text": "\n".join(text)}]
    for img in st.get("images", []):
        with open(os.path.join(AI_DIR, "images", img["file"]), "rb") as f:
            data = base64.b64encode(f.read()).decode()
        parts.append({"text": f"[Screenshot {img['file']} from message {img['msg']}]"})
        parts.append({"inlineData": {"mimeType": img["mime"], "data": data}})
    return parts


def short_path(p):
    """'ScreenNotes/app/src/main/java/com/example/screennotes/MainActivity.kt' -> 'MainActivity.kt'"""
    p = re.sub(r"^ScreenNotes/app/src/main/java/(?:[a-z0-9_]+/)*?screennotes/", "", p)
    return re.sub(r"^ScreenNotes/", "", p)


EVENT_TEXT = {
    "thought": "💭 {}",
    "progress": "📢 {}",
    "read": "📖 Reading `{}`",
    "list": "📂 Looking at files in `{}`",
    "search": "🔎 Searching the code for `{}`",
    "edit": "✏️ Edited `{}`",
    "create": "🆕 Created `{}`",
    "delete": "🗑️ Deleted `{}`",
    "build_start": "🔨 Building the app…",
    "build_ok": "✅ The app builds",
    "build_fail": "❌ Build failed ({}) — fixing it",
    "wait": "⏳ {}",
}


def cmd_agent():
    st = load_state()
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        raise UserFacingError(
            "The AI isn't set up yet: add your Gemini API key on GitHub as a repository secret named "
            "GEMINI_API_KEY (repo Settings → Secrets and variables → Actions → New repository secret), "
            "then tap Try again.")
    agents_md = read_text("AGENTS.md")
    system = SYSTEM_RULES + ("\n\n# Project notes (AGENTS.md)\n\n" + agents_md if agents_md else "")

    def on_event(kind, text):
        if kind == "build_start":
            set_step(st, 3, "active")
        elif kind == "build_ok":
            set_step(st, 3, "done", STEPS[3])
        elif kind == "build_fail":
            set_step(st, 3, "active", "Building the app to check it works (fixing errors)")
        if kind in ("list",):
            return
        if kind == "build_fail":
            text = "1 error" if text == "1" else f"{text} errors"
        elif kind in ("read", "edit", "create", "delete"):
            text = short_path(text)
        msg = EVENT_TEXT.get(kind, "{}").format(text)
        add_activity(st, msg[:160])
        post_status(st)

    models = [m for m in dict.fromkeys([os.environ.get("GEMINI_MODEL") or "gemini-3.8-flash",
                                        os.environ.get("GEMINI_FALLBACK_MODEL") or ""]) if m]
    result, last_error = None, None
    for i, model in enumerate(models):
        st["model"] = model
        if i > 0:
            add_activity(st, f"⚠️ Switching to {model} (the first model's daily limit is used up)")
            st["extra_note"] = ("Another AI model started on this request but hit its usage limit. Any changes "
                                "it made are already in the files (see the diff above, if any). Check them and finish the job.")
        post_status(st, force=True)
        agent = gemini_agent.Agent(key, model, system, on_event=on_event,
                                   max_turns=int(os.environ.get("AI_MAX_TURNS") or 60),
                                   max_minutes=int(os.environ.get("AI_MAX_MINUTES") or 28))
        try:
            result = agent.run(build_first_message(st))
            print(f"Gemini usage ({model}): {agent.usage}")
            break
        except gemini_agent.QuotaError as e:
            print(f"::warning::{e}")
            last_error = e
            continue
        except gemini_agent.AgentError as e:
            raise UserFacingError(str(e))
    if result is None:
        raise UserFacingError(
            "Gemini's usage limit is reached for today (free keys only allow a few requests). "
            "Try again tomorrow, or enable billing for your key in Google AI Studio. "
            f"Details: {last_error}")
    st["outcome"] = result["outcome"]
    st["reply"] = result["reply"]
    set_step(st, 2, "done", "AI changed the code" if result["outcome"] == "changed" else "AI looked into it")
    save_state(st)
    post_status(st, force=True)


# --------------------------------------------------------------------------- publish
def final_build(st):
    env = gemini_agent.clean_env()
    env.update(APP_GIT_SHA=st["sha"], APP_BUILD_LABEL=f"update #{st['issue']}",
               APP_BUILD_TIME=str(int(time.time() * 1000)))
    p = subprocess.run(["gradle", "assembleDebug", "--console=plain"], cwd="ScreenNotes", env=env,
                       capture_output=True, text=True, timeout=1200)
    if p.returncode != 0:
        print(p.stdout[-5000:], p.stderr[-5000:])
        raise UserFacingError("The final build failed:\n" + gradle_errors.summarize(p.stdout + p.stderr, 1500))


def cmd_publish():
    st = load_state()
    n, branch, base = st["issue"], st["branch"], st["base"]
    outcome = st.get("outcome", "no_change")
    changed = gemini_agent.git_changed_files()
    ahead = git_out("rev-list", "--count", f"origin/{base}..HEAD").strip() != "0"

    if outcome == "changed" and not changed and not ahead:
        outcome = "no_change"
    if outcome != "changed":
        # A question or plain answer: don't publish half-finished edits.
        git("checkout", "--", ".", check=False)
        git("clean", "-fdq", "--", "ScreenNotes", check=False)
        for i in (3, 4):
            if st["steps"][i][1] != "done":
                set_step(st, i, "skipped")
        st["state"] = "question" if outcome == "needs_info" else "done"
        save_state(st)
        # Final comment first, then the label: the app watches for the label change.
        post_status(st, force=True)
        set_ai_label(n, "ai:question" if outcome == "needs_info" else "ai:replied")
        return

    set_step(st, 3, "done")
    set_step(st, 4, "active")
    add_activity(st, "📦 Publishing the test version…")
    post_status(st, force=True)

    if changed:
        title_line = re.sub(r"\s+", " ", st["title"])[:72]
        summary = re.sub(r"\s+", " ", st.get("reply", ""))[:400]
        git("add", "-A", "--", "ScreenNotes")
        git("commit", "-q", "-m", f"AI update #{n}: {title_line}\n\n{summary}\n\nRequested in #{n}.")
    st["sha"] = git_out("rev-parse", "HEAD").strip()
    final_build(st)

    push_args = ["--force-with-lease"] if st.get("restarted") else []
    git_push(*push_args, "origin", f"HEAD:refs/heads/{branch}")
    st["compare"] = f"{SERVER}/{REPO}/compare/{base}...{urllib.parse.quote(branch, safe='')}"

    # Pull request (Approve & merge in the app merges it; the app can also create it if this fails)
    owner = REPO.split("/")[0]
    pr_body = (f"Closes #{n}\n\n{st.get('reply', '')}\n\n---\n"
               f"Made by the AI updater for #{n}. Test the APK linked there, then approve in the app.")
    prs = gh("GET", f"/repos/{REPO}/pulls?state=open&head={urllib.parse.quote(owner + ':' + branch)}") or []
    try:
        if prs:
            pr = prs[0]
            gh("PATCH", f"/repos/{REPO}/pulls/{pr['number']}", {"body": pr_body})
        else:
            pr = gh("POST", f"/repos/{REPO}/pulls", {"title": f"AI update #{n}: {st['title']}"[:250],
                                                    "head": branch, "base": base, "body": pr_body})
        st["pr"] = pr["number"]
    except GitHubError as e:
        print(f"::warning::Couldn't open a pull request ({e.code}). To allow it: repo Settings → Actions → "
              "General → 'Allow GitHub Actions to create and approve pull requests'. The app will create it "
              "when you tap Approve & merge.")
        st["pr"] = None

    # Test APK as a pre-release (doesn't replace the 'Latest' release of main)
    tag = f"update-{n}-{st['sha'][:7]}"
    apk_name = f"ScreenNotes-update-{n}.apk"
    old = gh("GET", f"/repos/{REPO}/releases/tags/{tag}", ok404=True)
    if old:
        gh("DELETE", f"/repos/{REPO}/releases/{old['id']}")
    rel = gh("POST", f"/repos/{REPO}/releases", {
        "tag_name": tag, "target_commitish": st["sha"], "name": f"Test version for update #{n}",
        "body": f"Test build for #{n}: {st['title']}\n\nInstall it on your device to try the change. "
                f"If it works, tap **Approve & merge** in the app.",
        "prerelease": True, "make_latest": "false"})
    with open(APK_PATH, "rb") as f:
        apk = f.read()
    upload = rel["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(apk_name)
    asset = gh("POST", upload, body=apk, content_type="application/vnd.android.package-archive")
    st.update(apk=asset["browser_download_url"], apk_api=asset["url"], apk_name=apk_name)
    for r in paginate(f"/repos/{REPO}/releases"):
        if r["tag_name"].startswith(f"update-{n}-") and r["id"] != rel["id"]:
            try:
                gh("DELETE", f"/repos/{REPO}/releases/{r['id']}")
                gh("DELETE", f"/repos/{REPO}/git/refs/tags/{urllib.parse.quote(r['tag_name'])}", ok404=True)
            except GitHubError as e:
                print(f"::warning::Couldn't delete old test build {r['tag_name']}: {e}")

    set_step(st, 4, "done", "Published a test version")
    add_activity(st, "🎉 Test version ready")
    st["state"] = "done"
    save_state(st)
    post_status(st, force=True)
    set_ai_label(n, "ai:done")


# --------------------------------------------------------------------------- fail
class UserFacingError(Exception):
    """An error whose message is shown to the owner as-is."""


def cmd_fail():
    st = load_state()
    if not st.get("issue"):
        n = int(os.environ.get("ISSUE_NUMBER") or 0)
        if not n:
            return
        st = {"issue": n, "steps": [[t, "todo"] for t in STEPS], "activity": [],
              "model": os.environ.get("GEMINI_MODEL")}
    if st.get("state") in ("done", "question"):
        return
    for step in st.get("steps", []):
        if step[1] == "active":
            step[1] = "failed"
            break
    st["state"] = "failed"
    if not st.get("error"):
        st["error"] = (read_text(os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "sn-ai-error.txt")).strip()
                       or "Something went wrong on GitHub. Open the run log for details.")
    save_state(st)
    post_status(st, force=True)
    set_ai_label(st["issue"], "ai:failed")


# --------------------------------------------------------------------------- main
def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    handlers = {"start": cmd_start, "agent": cmd_agent, "publish": cmd_publish, "fail": cmd_fail}
    if cmd not in handlers:
        print(__doc__)
        return 2
    try:
        handlers[cmd]()
    except UserFacingError as e:
        record_error(str(e))
        print(f"::error::{e}")
        return 1
    except Exception as e:
        record_error(f"Unexpected problem in the '{cmd}' step: {type(e).__name__}: {str(e)[:300]}")
        raise
    return 0


def record_error(msg):
    st = load_state()
    if st:
        st["error"] = msg
        save_state(st)
    with open(os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "sn-ai-error.txt"), "w", encoding="utf-8") as f:
        f.write(msg)


if __name__ == "__main__":
    sys.exit(main())
