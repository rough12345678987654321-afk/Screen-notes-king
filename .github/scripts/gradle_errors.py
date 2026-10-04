#!/usr/bin/env python3
"""Pull the useful error lines out of a Gradle / Kotlin build log.

Used two ways:
  * by the AI updater (imported) to show the AI a short, precise list of build errors;
  * from the command line after a failed build, to turn errors into GitHub annotations:
        python3 .github/scripts/gradle_errors.py build.log
"""
import os
import re
import sys

_PATTERNS = [
    # Kotlin:  e: file:///abs/path/File.kt:12:5 Unresolved reference 'foo'.
    re.compile(r"^e: (?:file://)?(?P<file>/[^:\n]+?\.kts?):(?P<line>\d+):(?P<col>\d+) (?P<msg>.+)$"),
    # KSP (Room etc.):  e: [ksp] /abs/path/File.kt:12: message
    re.compile(r"^e: \[ksp\] (?P<file>/[^:\n]+?):(?P<line>\d+): (?P<msg>.+)$"),
    # Java:  /abs/path/File.java:12: error: message
    re.compile(r"^(?P<file>/[^:\n]+?\.java):(?P<line>\d+): error: (?P<msg>.+)$"),
    # Android resources / manifest:  /abs/path/res/xml/x.xml:3: AAPT: error: message
    re.compile(r"^(?:ERROR: ?)?(?P<file>/[^:\n]+?\.xml):(?P<line>\d+)(?::[\d-]+)*:? (?:AAPT: )?[Ee]rror:? (?P<msg>.+)$"),
]
# Any other compiler error line that has no usable location.
_GENERIC = re.compile(r"^e: (?P<msg>.+)$")


def _relative(path):
    """Show paths relative to the repository (what humans and the AI understand)."""
    for root in (os.environ.get("GITHUB_WORKSPACE"), os.getcwd()):
        if root and path.startswith(root.rstrip("/") + "/"):
            return path[len(root.rstrip("/")) + 1:]
    m = re.search(r"(ScreenNotes/.+)$", path)
    return m.group(1) if m else path


def extract(log):
    """Return (errors, what_went_wrong).

    errors: list of dicts {file, line, col, msg} (file may be None), de-duplicated, in order.
    what_went_wrong: Gradle's own summary of the failure ('' if none).
    """
    errors, seen = [], set()
    for raw in log.splitlines():
        line = raw.rstrip()
        hit = None
        for pat in _PATTERNS:
            m = pat.match(line)
            if m:
                d = m.groupdict()
                hit = {
                    "file": _relative(d["file"]),
                    "line": int(d["line"]),
                    "col": int(d.get("col") or 1),
                    "msg": d["msg"].strip(),
                }
                break
        if hit is None:
            m = _GENERIC.match(line)
            if m:
                hit = {"file": None, "line": 0, "col": 0, "msg": m.group("msg").strip()}
        if hit:
            key = (hit["file"], hit["line"], hit["msg"])
            if key not in seen:
                seen.add(key)
                errors.append(hit)

    what = ""
    m = re.search(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\n\* Exception is:|\Z)", log, re.S)
    if m:
        what = m.group(1).strip()
    return errors, what


def summarize(log, limit=6000):
    """Short human/AI-readable error report (empty string if nothing found)."""
    errors, what = extract(log)
    out = []
    for e in errors[:40]:
        where = f"{e['file']}:{e['line']}:{e['col']}: " if e["file"] else ""
        out.append(f"- {where}{e['msg']}")
    if len(errors) > 40:
        out.append(f"- ... and {len(errors) - 40} more errors")
    if what:
        out.append("\nGradle says:\n" + what)
    text = "\n".join(out).strip()
    if not text:
        # Nothing matched: fall back to the end of the log, which usually holds the failure.
        text = "\n".join(log.strip().splitlines()[-60:])
    return text[:limit]


def _escape(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv):
    if len(argv) < 2:
        print("usage: gradle_errors.py <build.log>", file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        log = f.read()
    errors, what = extract(log)
    for e in errors[:20]:
        if e["file"]:
            print(f"::error file={e['file']},line={e['line']},col={e['col']},title=Build error::{_escape(e['msg'])}")
        else:
            print(f"::error title=Build error::{_escape(e['msg'])}")
    if what and not errors:
        print(f"::error title=Build failed::{_escape(what[:1500])}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### Build errors\n\n```\n" + summarize(log) + "\n```\n")
    if not errors and not what:
        print("No compiler errors found in the log; see the build step output.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
