#!/usr/bin/env python3
"""A small, safe coding agent built on the Gemini API (generateContent + function calling).

Gemini can only use the tools defined below: list/read/search files, edit files inside
ScreenNotes/, compile the app, report progress and finish. It cannot run shell commands,
so it never sees the GitHub token or other secrets.

Used by app_update.py (the AI updater workflow).
"""
import json
import os
import posixpath
import re
import subprocess
import time
import urllib.error
import urllib.request

import gradle_errors

GEMINI_API = os.environ.get("GEMINI_API_BASE", "https://generativelanguage.googleapis.com/v1beta")
APP_DIR = "ScreenNotes"
WRITABLE_PREFIX = "ScreenNotes/"
PROTECTED = {"ScreenNotes/app/debug.keystore"}
HIDDEN_DIRS = {".git", ".ai", ".gradle", "build", ".idea", ".kotlin", "__pycache__", "node_modules"}
TEXT_EXT = {".kt", ".kts", ".java", ".xml", ".md", ".properties", ".pro", ".txt", ".json", ".toml",
            ".gradle", ".yml", ".yaml", ".py", ".cfg", ".gitignore"}
SECRET_ENV = {"GITHUB_TOKEN", "GH_TOKEN", "GEMINI_API_KEY", "ACTIONS_RUNTIME_TOKEN",
              "ACTIONS_ID_TOKEN_REQUEST_TOKEN", "ACTIONS_ID_TOKEN_REQUEST_URL"}


class QuotaError(Exception):
    """The model's daily quota is used up (another model may still work)."""


class AgentError(Exception):
    """Something went wrong that the AI can't recover from."""


class ToolError(Exception):
    pass


def S(type_, desc=None, **kw):
    d = {"type": type_}
    if desc:
        d["description"] = desc
    d.update(kw)
    return d


TOOLS = [
    {
        "name": "report_progress",
        "description": "Tell the owner, in plain friendly words, what you're doing right now "
                       "(e.g. 'Adding a dark mode switch to the home screen'). Call it at the start and "
                       "whenever you move on to a new part of the work. Under 100 characters.",
        "parameters": S("OBJECT", properties={"message": S("STRING")}, required=["message"]),
    },
    {
        "name": "list_files",
        "description": "List the files in a folder of the repository (recursively), with sizes.",
        "parameters": S("OBJECT", properties={"path": S("STRING", "Folder, e.g. 'ScreenNotes/app' or '.'")},
                        required=["path"]),
    },
    {
        "name": "read_file",
        "description": "Read a text file. Every line is prefixed with its number and '| ' "
                       "(the prefix is NOT part of the file).",
        "parameters": S("OBJECT", properties={
            "path": S("STRING"),
            "start_line": S("INTEGER", "Optional first line (1-based)"),
            "end_line": S("INTEGER", "Optional last line"),
        }, required=["path"]),
    },
    {
        "name": "search_code",
        "description": "Search the code with a regular expression (case-insensitive). "
                       "Returns matching lines as path:line: text.",
        "parameters": S("OBJECT", properties={
            "pattern": S("STRING"),
            "path": S("STRING", "Optional folder to search (default: ScreenNotes)"),
        }, required=["pattern"]),
    },
    {
        "name": "edit_file",
        "description": "Replace one exact piece of text in a file. old_text must match the file "
                       "exactly (same indentation, no line-number prefixes) and appear exactly once: "
                       "include a few surrounding lines to make it unique. To insert code, replace a "
                       "nearby line with that same line plus the new code.",
        "parameters": S("OBJECT", properties={
            "path": S("STRING"), "old_text": S("STRING"), "new_text": S("STRING"),
        }, required=["path", "old_text", "new_text"]),
    },
    {
        "name": "write_file",
        "description": "Create a new file, or replace a whole file. Prefer edit_file for changes "
                       "to existing files.",
        "parameters": S("OBJECT", properties={"path": S("STRING"), "content": S("STRING")},
                        required=["path", "content"]),
    },
    {
        "name": "delete_file",
        "description": "Delete a file inside ScreenNotes/.",
        "parameters": S("OBJECT", properties={"path": S("STRING")}, required=["path"]),
    },
    {
        # No parameters: Gemini rejects OBJECT schemas with empty 'properties'.
        "name": "run_build",
        "description": "Compile the Android app (gradle assembleDebug). Returns BUILD SUCCESSFUL or the "
                       "compiler errors. Takes 1-3 minutes. Run it after changing code and fix every "
                       "error before calling finish.",
    },
    {
        "name": "finish",
        "description": "Call exactly once when you're done. outcome: 'changed' = you changed the app "
                       "(the build must pass); 'needs_info' = you need the owner to answer a question "
                       "first (ask it in reply); 'no_change' = no code change was needed (e.g. you "
                       "answered a question). reply: a short friendly message for the owner in simple "
                       "English (Markdown OK, no code): what you changed and how to try it, or your question.",
        "parameters": S("OBJECT", properties={
            "outcome": S("STRING", enum=["changed", "needs_info", "no_change"]),
            "reply": S("STRING"),
        }, required=["outcome", "reply"]),
    },
]


def clean_env():
    """Environment for builds: everything except secrets."""
    return {k: v for k, v in os.environ.items() if k not in SECRET_ENV}


def git_changed_files():
    out = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"],
                         capture_output=True, text=True).stdout
    files = []
    for line in out.splitlines():
        path = line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        path = path.strip('"')
        if not path.startswith(".ai/"):
            files.append(path)
    return files


def first_sentence(text, limit=110):
    text = (text or "").strip()
    m = re.match(r"\*\*(.+?)\*\*", text)  # thought summaries usually start with a bold title
    if m:
        return m.group(1).strip()[:limit]
    text = re.sub(r"\s+", " ", text)
    m = re.match(r"(.+?[.!?])(\s|$)", text)
    s = m.group(1) if m else text
    return s if len(s) <= limit else s[:limit - 1] + "…"


class Agent:
    def __init__(self, api_key, model, system_text, on_event=None, max_turns=60, max_minutes=30,
                 build_cmd=None):
        self.api_key = api_key
        self.model = model
        self.system_text = system_text
        self.on_event = on_event or (lambda kind, text: None)
        self.max_turns = max_turns
        self.deadline = time.time() + max_minutes * 60
        self.build_cmd = build_cmd or ["gradle", "assembleDebug", "--console=plain"]
        self.thoughts_ok = True
        self.turns = 0
        self.builds = 0
        self.dirty = False            # files changed since the last successful build
        self.last_build_ok = None
        self.result = None
        self.usage = {"prompt": 0, "output": 0, "requests": 0}

    # ---------------------------------------------------------------- Gemini API
    def _post(self, body):
        url = f"{GEMINI_API}/models/{self.model}:generateContent"
        req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers={
            "Content-Type": "application/json", "x-goog-api-key": self.api_key})
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, {"error": {"message": raw[:500]}}
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            return 0, {"error": {"message": f"network error: {e}"}}

    def generate(self, contents):
        body = {
            "systemInstruction": {"parts": [{"text": self.system_text}]},
            "contents": contents,
            "tools": [{"functionDeclarations": TOOLS}],
            "toolConfig": {"functionCallingConfig": {"mode": "AUTO"}},
        }
        last = ""
        for attempt in range(8):
            if self.thoughts_ok:
                body["generationConfig"] = {"thinkingConfig": {"includeThoughts": True}}
            else:
                body.pop("generationConfig", None)
            code, data = self._post(body)
            if code == 200:
                self.usage["requests"] += 1
                meta = data.get("usageMetadata") or {}
                self.usage["prompt"] += meta.get("promptTokenCount", 0)
                self.usage["output"] += meta.get("candidatesTokenCount", 0) + meta.get("thoughtsTokenCount", 0)
                if not data.get("candidates"):
                    raise AgentError(f"Gemini refused to answer: {data.get('promptFeedback') or data}")
                return data
            err = (data or {}).get("error") or {}
            msg = err.get("message") or str(data)[:300]
            last = f"{code}: {msg}"
            if code == 400 and self.thoughts_ok and re.search(r"thinking|thought", msg, re.I):
                self.thoughts_ok = False  # this model doesn't support thought summaries
                continue
            if code == 400 and re.search(r"API key not valid|API_KEY_INVALID", msg):
                raise AgentError("Your Gemini API key was rejected. Check the GEMINI_API_KEY secret on GitHub.")
            if code == 404 or (code == 400 and "model" in msg.lower() and "not found" in msg.lower()):
                raise AgentError(f"Gemini model '{self.model}' isn't available: {msg}")
            if code == 429:
                if self._is_daily_quota(err):
                    raise QuotaError(f"{self.model}: daily quota used up ({msg[:200]})")
                delay = self._retry_delay(err) or 15 * (attempt + 1)
                self.on_event("wait", f"Gemini is rate-limiting us, waiting {int(delay)} s")
                time.sleep(min(delay, 90))
                continue
            if code in (0, 500, 502, 503, 504):
                time.sleep(5 * (attempt + 1))
                continue
            raise AgentError(f"Gemini API error {last}")
        if "429" in last:
            raise QuotaError(f"{self.model}: still rate-limited after several tries ({last[:200]})")
        raise AgentError(f"Gemini kept failing ({last})")

    @staticmethod
    def _is_daily_quota(err):
        text = json.dumps(err)
        return bool(re.search(r"PerDay|per day|daily", text, re.I))

    @staticmethod
    def _retry_delay(err):
        for d in err.get("details") or []:
            if str(d.get("@type", "")).endswith("RetryInfo"):
                m = re.match(r"([\d.]+)s", str(d.get("retryDelay", "")))
                if m:
                    return float(m.group(1)) + 1
        return None

    # ---------------------------------------------------------------- main loop
    def run(self, first_parts):
        contents = [{"role": "user", "parts": first_parts}]
        nudges = 0
        while True:
            if self.turns >= self.max_turns or time.time() > self.deadline:
                return self._out_of_budget()
            self.turns += 1
            data = self.generate(contents)
            cand = data["candidates"][0]
            content = cand.get("content") or {}
            parts = content.get("parts") or []
            # Send the model's turn back exactly as received: Gemini 3 needs its thought signatures.
            contents.append({"role": "model", "parts": parts} if not content.get("role") else content)

            for p in parts:
                if p.get("thought") and p.get("text"):
                    self.on_event("thought", first_sentence(p["text"]))
            calls = [p["functionCall"] for p in parts if "functionCall" in p]

            if not calls:
                text = "".join(p.get("text", "") for p in parts if not p.get("thought")).strip()
                reason = cand.get("finishReason", "")
                if reason == "MALFORMED_FUNCTION_CALL":
                    hint = "Your last function call was malformed. Please call the tool again with valid arguments."
                else:
                    nudges += 1
                    if nudges >= 3 and text:
                        # The model keeps answering in plain text: treat it as its final reply.
                        return self._finish_from_text(text)
                    hint = ("Please continue by calling the tools: make the change with edit_file/write_file, "
                            "check it with run_build, then call finish. If you need the owner to answer "
                            "something first, call finish with outcome 'needs_info'.")
                if not parts:
                    contents.pop()  # don't send an empty model turn back
                contents.append({"role": "user", "parts": [{"text": hint}]})
                continue

            responses = []
            for call in calls:
                name = call.get("name", "")
                args = call.get("args") or {}
                try:
                    result = self.execute(name, args)
                except ToolError as e:
                    result = {"error": str(e)}
                except Exception as e:  # never let one bad tool call kill the run
                    result = {"error": f"{type(e).__name__}: {e}"}
                fr = {"name": name, "response": result}
                if call.get("id"):
                    fr["id"] = call["id"]
                responses.append({"functionResponse": fr})
                if self.result is not None:
                    return self.result
            contents.append({"role": "user", "parts": responses})

    def _finish_from_text(self, text):
        changed = git_changed_files()
        if changed and not self._ensure_build_ok():
            raise AgentError("The AI stopped before the app compiled.")
        self.result = {"outcome": "changed" if changed else "no_change", "reply": text}
        return self.result

    def _out_of_budget(self):
        changed = git_changed_files()
        if changed and self._ensure_build_ok():
            self.result = {
                "outcome": "changed",
                "reply": "I made changes but ran out of time before I could fully double-check them, "
                         "so please test carefully. Changed files: " + ", ".join(f"`{f}`" for f in changed[:10]),
            }
            return self.result
        raise AgentError(f"The AI ran out of time/steps ({self.turns} steps) without a working change.")

    def _ensure_build_ok(self):
        if self.dirty or not self.last_build_ok:
            return self.tool_run_build().get("result") == "BUILD SUCCESSFUL"
        return True

    # ---------------------------------------------------------------- tools
    def execute(self, name, args):
        fn = getattr(self, f"tool_{name}", None)
        if fn is None:
            raise ToolError(f"Unknown tool '{name}'")
        return fn(**args) if args else fn()

    @staticmethod
    def _path(p, write=False):
        p = str(p or "").strip().replace("\\", "/")
        if p.startswith("./"):
            p = p[2:]
        norm = posixpath.normpath(p) if p else "."
        if norm.startswith("../") or norm == ".." or norm.startswith("/"):
            raise ToolError("Paths must be inside the repository, e.g. ScreenNotes/app/build.gradle.kts")
        if norm.split("/")[0] in {".git", ".ai"}:
            raise ToolError("That folder is off-limits.")
        if write:
            if not norm.startswith(WRITABLE_PREFIX):
                raise ToolError("You can only create or change files inside ScreenNotes/.")
            if norm in PROTECTED:
                raise ToolError("Don't touch the signing key: updates must install over the existing app.")
        return norm

    def tool_report_progress(self, message=""):
        self.on_event("progress", str(message)[:140])
        return {"result": "shown to the owner"}

    def tool_list_files(self, path="."):
        root = self._path(path)
        if not os.path.exists(root):
            raise ToolError(f"No such folder: {root}")
        out = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if d not in HIDDEN_DIRS)
            for f in sorted(filenames):
                full = posixpath.join(dirpath.replace(os.sep, "/"), f)
                out.append(f"{posixpath.normpath(full)} ({os.path.getsize(full)} bytes)")
                if len(out) >= 400:
                    out.append("... (list truncated)")
                    return {"files": "\n".join(out)}
        self.on_event("list", root)
        return {"files": "\n".join(out) or "(empty)"}

    def tool_read_file(self, path, start_line=None, end_line=None):
        p = self._path(path)
        if not os.path.isfile(p):
            raise ToolError(f"File not found: {p}")
        if os.path.splitext(p)[1].lower() not in TEXT_EXT and os.path.basename(p) not in {"AGENTS.md"}:
            if os.path.getsize(p) > 0 and b"\0" in open(p, "rb").read(4096):
                raise ToolError("That's a binary file.")
        with open(p, encoding="utf-8", errors="replace") as f:
            lines = f.read().split("\n")
        start = max(1, int(start_line or 1))
        end = min(len(lines), int(end_line or len(lines)))
        if end - start > 1500:
            end = start + 1500
        body = "\n".join(f"{i}| {lines[i - 1]}" for i in range(start, end + 1))
        if end < len(lines):
            body += f"\n... (file has {len(lines)} lines; ask for start_line={end + 1} to read more)"
        self.on_event("read", p)
        return {"content": body}

    def tool_search_code(self, pattern, path=APP_DIR):
        root = self._path(path or APP_DIR)
        try:
            rx = re.compile(pattern, re.I)
        except re.error as e:
            raise ToolError(f"Bad regular expression: {e}")
        hits = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if d not in HIDDEN_DIRS)
            for f in sorted(filenames):
                full = posixpath.normpath(posixpath.join(dirpath.replace(os.sep, "/"), f))
                if os.path.splitext(f)[1].lower() not in TEXT_EXT:
                    continue
                try:
                    with open(full, encoding="utf-8", errors="replace") as fh:
                        for n, line in enumerate(fh, 1):
                            if rx.search(line):
                                hits.append(f"{full}:{n}: {line.rstrip()[:200]}")
                                if len(hits) >= 80:
                                    break
                except OSError:
                    continue
            if len(hits) >= 80:
                hits.append("... (more matches not shown)")
                break
        self.on_event("search", pattern)
        return {"matches": "\n".join(hits) or "no matches"}

    def tool_edit_file(self, path, old_text, new_text):
        p = self._path(path, write=True)
        if not os.path.isfile(p):
            raise ToolError(f"File not found: {p}. Use write_file to create a new file.")
        with open(p, encoding="utf-8") as f:
            src = f.read()
        if old_text == "":
            raise ToolError("old_text is empty. Use write_file to replace a whole file.")
        count = src.count(old_text)
        if count == 0:
            stripped = re.sub(r"(?m)^\s*\d+\| ", "", old_text)  # model pasted read_file prefixes
            if stripped != old_text and src.count(stripped) == 1:
                old_text, count = stripped, 1
            else:
                first = old_text.strip().splitlines()[0].strip() if old_text.strip() else ""
                near = [f"{i}| {l}" for i, l in enumerate(src.split("\n"), 1) if first and first in l][:5]
                hint = ("\nLines containing its first line:\n" + "\n".join(near)) if near else ""
                raise ToolError("old_text was not found in the file (check spaces/indentation; read the "
                                "file again first)." + hint)
        if count > 1:
            raise ToolError(f"old_text appears {count} times. Include more surrounding lines so it is unique.")
        updated = src.replace(old_text, new_text, 1)
        with open(p, "w", encoding="utf-8") as f:
            f.write(updated)
        self.dirty = True
        self.on_event("edit", p)
        line = src[:src.index(old_text)].count("\n") + 1
        return {"result": f"Edited {p} (around line {line})."}

    def tool_write_file(self, path, content):
        p = self._path(path, write=True)
        existed = os.path.exists(p)
        os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(content)
        self.dirty = True
        self.on_event("edit" if existed else "create", p)
        return {"result": f"{'Replaced' if existed else 'Created'} {p} ({content.count(chr(10)) + 1} lines)."}

    def tool_delete_file(self, path):
        p = self._path(path, write=True)
        if not os.path.isfile(p):
            raise ToolError(f"File not found: {p}")
        os.remove(p)
        self.dirty = True
        self.on_event("delete", p)
        return {"result": f"Deleted {p}."}

    def tool_run_build(self):
        self.on_event("build_start", "")
        self.builds += 1
        try:
            proc = subprocess.run(self.build_cmd, cwd=APP_DIR, env=clean_env(), capture_output=True,
                                  text=True, timeout=900)
            log, ok = proc.stdout + "\n" + proc.stderr, proc.returncode == 0
        except subprocess.TimeoutExpired:
            log, ok = "The build took longer than 15 minutes and was stopped.", False
        except FileNotFoundError as e:
            raise AgentError(f"Can't run the build: {e}")
        self.last_build_ok = ok
        if ok:
            self.dirty = False
            self.on_event("build_ok", "")
            return {"result": "BUILD SUCCESSFUL"}
        errors, _ = gradle_errors.extract(log)
        self.on_event("build_fail", str(len(errors) or 1))
        return {"result": "BUILD FAILED", "errors": gradle_errors.summarize(log, 6000)}

    def tool_finish(self, outcome="changed", reply=""):
        outcome = outcome if outcome in ("changed", "needs_info", "no_change") else "changed"
        reply = (reply or "").strip()
        if not reply:
            raise ToolError("Please include a reply for the owner.")
        changed = git_changed_files()
        if outcome == "changed":
            if not changed:
                raise ToolError("No files have changed. Make the change first, or use outcome "
                                "'no_change' / 'needs_info'.")
            if self.dirty or not self.last_build_ok:
                r = self.tool_run_build()
                if r.get("result") != "BUILD SUCCESSFUL":
                    return {"error": "Can't finish yet: the app doesn't compile. Fix these errors, then "
                                     "call finish again:\n" + r.get("errors", "")}
        self.result = {"outcome": outcome, "reply": reply}
        return {"result": "ok"}
