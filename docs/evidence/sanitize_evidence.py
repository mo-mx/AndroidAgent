#!/usr/bin/env python3
"""Mask host-local paths in the JUnit/probe evidence files under docs/evidence.

Why: review 6557962727 flagged that the published JUnit summary and the JDK
probe output contained the submitter's Windows user directory.  The numbers,
test names and assertion texts must stay untouched; only the host path prefix
and the random temp-directory suffix are masked.

What it replaces (and nothing else):
  <drive>:\\Users\\<name>\\...       -> C:\\Users\\<user>\\...
  C:\\Users\\<user>\\AppData\\Local\\Temp\\junit<digits>   -> %TEMP%\\junit<n>
  C:\\Users\\<user>\\AppData\\Local\\Temp\\probe<digits>   -> %TEMP%\\probe<n>

Usage:
  python docs/evidence/sanitize_evidence.py            # rewrite in place
  python docs/evidence/sanitize_evidence.py --check    # exit 1 if anything to mask

After rewriting, prints the recomputed totals of the JUnit summaries so the
masking can be shown not to have changed any count.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TARGETS = [
    ROOT / "pr4-a6a498f-junit-summary.txt",
    ROOT / "pr4-197dd90-junit-summary.txt",
    ROOT / "pr4-a6a498f-platform-probe" / "output-windows11-jdk17.txt",
]

USER_DIR = re.compile(r"[A-Za-z]:\\Users\\[^\\\s]+\\")
TEMP = re.compile(r"[A-Za-z]:\\Users\\[^\\\s]+\\AppData\\Local\\Temp\\(junit|probe)\d+")


def mask(text: str) -> str:
    text = TEMP.sub(lambda m: "%TEMP%\\" + m.group(1) + "<n>", text)
    return USER_DIR.sub(r"C:\\Users\\<user>\\", text)


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
    check = "--check" in sys.argv
    dirty = False
    for path in TARGETS:
        if not path.exists():
            print(f"SKIP missing: {path}")
            continue
        before = path.read_text(encoding="utf-8")
        after = mask(before)
        if before != after:
            dirty = True
            print(f"{'WOULD MASK' if check else 'MASKED'}: {path.relative_to(ROOT.parent)}")
            if not check:
                path.write_text(after, encoding="utf-8", newline="")
        if path.name.endswith("junit-summary.txt"):
            declared, summed = totals(path)
            ok = declared == summed
            print(f"  totals declared={declared} summed_from_rows={summed} match={ok}")
            if not ok:
                return 2
    if check and dirty:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
