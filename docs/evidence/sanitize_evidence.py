#!/usr/bin/env python3
"""Mask host-local user paths in published evidence and docs.

Why: review 6557962727 (F2) flagged that the published JUnit summary and the JDK
probe output contained the submitter's Windows user directory.  Round 57 fixed
`docs/evidence/` but explicitly left `docs/phase1-device-report.md` and
`docs/phase2-basic-agent-report.md` alone ("如需一并脱敏请说一声").  Round 58
extends the same rule to the whole `docs/` tree so the repo does not carry a
personal home-directory path, and adds a `--check` gate over `docs/`.

Invariant: only path prefixes are rewritten.  Test class names, counts,
assertion texts and prose meaning are untouched.  `--check` re-verifies that by
re-summing every per-class row of the JUnit summaries and comparing the sum with
the declared total; a mismatch exits 2 (that guard predates this extension).

Patterns (nothing else is replaced):
  <drive>:\\Users\\<local-user>\\...            -> C:\\Users\\<user>\\...
  Users<local-user>AppData / Users<local-user>…  -> Users<user>AppData / Users<user>…
      (covers backslash-eaten reproductions such as C:Users<name>AppDataLocalTemp...)
  C:\\Users\\<user>\\AppData\\Local\\Temp\\junit<digits> -> %TEMP%\\junit<n>
  C:\\Users\\<user>\\AppData\\Local\\Temp\\probe<digits> -> %TEMP%\\probe<n>

The local user name is read from the environment (USERNAME / USER / getpass).
It is never written into the repository.

Usage:
  python docs/evidence/sanitize_evidence.py                   # mask docs/evidence only (default)
  python docs/evidence/sanitize_evidence.py --scope docs      # mask docs/**/*.md, keep evidence untouched
  python docs/evidence/sanitize_evidence.py --scope docs --check   # exit 1 if anything is left to mask
  python docs/evidence/sanitize_evidence.py --scope all --check    # also reports KNOWN-OPEN files

Exit codes: 0 clean, 1 something left to mask (in --check), 2 JUnit totals mismatch.
"""
import getpass
import os
import re
import sys
from pathlib import Path

EVIDENCE_DIR = Path(__file__).resolve().parent
DOCS_DIR = EVIDENCE_DIR.parent
REPO_ROOT = DOCS_DIR.parent

EVIDENCE_TARGETS = [
    EVIDENCE_DIR / "pr4-a6a498f-junit-summary.txt",
    EVIDENCE_DIR / "pr4-197dd90-junit-summary.txt",
    EVIDENCE_DIR / "pr4-a6a498f-platform-probe" / "output-windows11-jdk17.txt",
]

# Files that legitimately still contain a host path and need a separate decision
# (they are functional inputs of a local harness, not prose).  Reported, not
# rewritten, and never silently treated as fixed.
KNOWN_OPEN = ["asr_eval/refs.json", "asr_eval/make_set.ps1"]


def local_user() -> str:
    for var in ("USERNAME", "USER", "LOGNAME"):
        v = os.environ.get(var)
        if v:
            return v
    try:
        return getpass.getuser()
    except Exception:  # pragma: no cover - extremely unlikely
        return ""


USER = local_user()

# Separators may appear single (markdown/prose) or doubled (JSON string literals
# such as `C:\\Users\\<name>\\...`).  Two passes keep the replacement shape
# identical to the input shape, so masking a JSON file cannot produce an
# invalid escape such as `\U`.
TEMP = re.compile(r"[A-Za-z]:\\{1,2}Users\\{1,2}[^\\\s\"]+\\{1,2}AppData\\{1,2}Local\\{1,2}Temp\\{1,2}(junit|probe)\d+")
TEMP_ESC = re.compile(r"[A-Za-z]:\\\\Users\\\\[^\\\s\"]+\\\\AppData\\\\Local\\\\Temp\\\\(junit|probe)\d+")
USER_DIR_ESC = re.compile(r"[A-Za-z]:\\\\Users\\\\[^\\\s\"]+\\\\")
USER_DIR = re.compile(r"[A-Za-z]:\\Users\\[^\\\s\"]+\\")
# `Users<name>AppData` / `Users<name>…` — the shape produced when bash eats the
# backslashes of a Windows path.  Anchored on the literal user name so it cannot
# rewrite an already-masked `<user>`.
if USER:
    EATEN = re.compile(r"Users" + re.escape(USER) + r"(?=AppData|Local|…|\.|\\)")
else:  # pragma: no cover
    EATEN = re.compile(r"(?!x)x")


def mask(text: str) -> str:
    # Escaped (JSON) shapes first: replacement keeps the doubled separators so the
    # file stays valid JSON and nothing else in the line changes.
    text = TEMP_ESC.sub(lambda m: "%TEMP%\\\\" + m.group(1) + "<n>", text)
    text = USER_DIR_ESC.sub(r"C:\\\\Users\\\\<user>\\\\", text)
    text = TEMP.sub(lambda m: "%TEMP%\\" + m.group(1) + "<n>", text)
    text = USER_DIR.sub(r"C:\\Users\\<user>\\", text)
    return EATEN.sub("Users<user>", text)


def doc_targets():
    return [p for p in sorted(DOCS_DIR.rglob("*.md")) if EVIDENCE_DIR not in p.parents]


def totals(path: Path):
    """Return (declared, summed) where both are (classes, tests, failures, errors, skipped)."""
    text = path.read_text(encoding="utf-8")
    declared = None
    for line in text.splitlines():
        m = re.match(r"TOTAL classes=(\d+) tests=(\d+) failures=(\d+) errors=(\d+) skipped=(\d+)", line)
        if m:
            declared = tuple(int(x) for x in m.groups())
            break
        m = re.match(r"# 总计：tests=(\d+) failures=(\d+) errors=(\d+) skipped=(\d+) classes=(\d+)", line)
        if m:
            t, f, e, s_, c = (int(x) for x in m.groups())
            declared = (c, t, f, e, s_)
            break
    rows = []
    for ln in text.splitlines():
        cells = [c.strip() for c in ln.split("|")]
        if len(cells) == 6 and cells[0].startswith("ai.closepaw") and cells[1].isdigit():
            rows.append(cells)
            continue
        parts = ln.split()
        if (len(parts) == 6 and parts[0].startswith("ai.closepaw")
                and all(x.isdigit() for x in parts[1:5])):
            rows.append(parts)
    summed = (len(rows),
              sum(int(r[1]) for r in rows),
              sum(int(r[2]) for r in rows),
              sum(int(r[3]) for r in rows),
              sum(int(r[4]) for r in rows))
    return declared, summed


def main() -> int:
    argv = sys.argv[1:]
    check = "--check" in argv
    scope = "evidence"
    if "--scope" in argv:
        i = argv.index("--scope")
        if i + 1 < len(argv):
            scope = argv[i + 1]

    if scope == "evidence":
        targets = list(EVIDENCE_TARGETS)
    elif scope == "docs":
        targets = doc_targets()
    elif scope == "all":
        targets = list(EVIDENCE_TARGETS) + doc_targets()
    else:
        print(f"unknown --scope {scope!r} (evidence|docs|all)")
        return 64

    dirty = False
    for path in targets:
        if not path.exists():
            print(f"SKIP missing: {path}")
            continue
        before = path.read_text(encoding="utf-8")
        after = mask(before)
        if before != after:
            dirty = True
            print(f"{'WOULD MASK' if check else 'MASKED'}: {path.relative_to(REPO_ROOT)}")
            if not check:
                path.write_text(after, encoding="utf-8", newline="")
        if path.name.endswith("junit-summary.txt"):
            declared, summed = totals(path)
            ok = declared == summed
            print(f"  totals declared={declared} summed_from_rows={summed} match={ok}")
            if not ok:
                return 2

    if scope == "all":
        for rel in KNOWN_OPEN:
            p = REPO_ROOT / rel
            if p.exists() and mask(p.read_text(encoding="utf-8")) != p.read_text(encoding="utf-8"):
                print(f"KNOWN-OPEN (needs separate decision, not rewritten): {rel}")

    if check and dirty:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
