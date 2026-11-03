# JDK platform probe — evidence for the 6 Windows-only JUnit failures

Provenance: written for round 55, re-verified and re-homed in round 57 after review
`6557962727` pointed out that (a) the file name did not match `public class Probe`,
so the published command `javac -d . Probe.java` could not be run on the file as
committed, and (b) the committed output leaked the submitter's Windows user directory.

## What this measures

`Probe.java` uses only JDK 17 APIs to answer four questions that the failing
assertions depend on:

1. does `File.renameTo` replace an **existing** target file (what
   `MemoryStore.atomicWrite` did before `197dd90`)?
2. does the same call succeed when the target is **missing**?
3. does `Files.move(..., REPLACE_EXISTING)` replace it?
4. does `new File(dir, "my-skill/SKILL.md").getPath()` end with the literal
   `my-skill/SKILL.md` on this host (the exact comparison in
   `AgentSkillCatalogTest :: entry filePath points to SKILL md`)?

## How to reproduce (verified verbatim in round 57)

Run from this directory, with a JDK 17. The repository vendors one under
`tools/jdk-17.0.20.1+1`, so no system JDK is required:

```bash
cd docs/evidence/pr4-a6a498f-platform-probe
JDK=../../../tools/jdk-17.0.20.1+1/bin      # or any JDK 17
OUT=$(mktemp -d)
OUT_ARG="$OUT"
command -v cygpath >/dev/null && OUT_ARG=$(cygpath -w "$OUT")   # Git Bash only
"$JDK/javac" -d "$OUT_ARG" Probe.java
"$JDK/java" -cp "$OUT_ARG" Probe
```

Verified in round 57 by running exactly these lines (Git Bash on Windows 11); exit
code 0 and the stdout below.

No copying or renaming step is needed: the file is now literally `Probe.java`
next to `public class Probe`, so `javac Probe.java` works as published.

## Observed output

Host: Windows 11, JDK 17.0.20.1+1 (Temurin, repository `tools/jdk-17.0.20.1+1`).
Verbatim stdout is in `output-windows11-jdk17.txt`:

```
renameTo over EXISTING target = false ; target content = old ; tmp still exists = true
renameTo over MISSING target  = true ; target content = new
Files.move(REPLACE_EXISTING) = true ; content = v3
getPath() = %TEMP%\probe<n>\my-skill\SKILL.md
endsWith("my-skill/SKILL.md") = false ; File.separator = '\' ; os.name = Windows 11
java.version = 17.0.20.1
```

The temp-directory name is masked to `%TEMP%\probe<n>`; the four boolean /
string results are unchanged from the raw run.

## What it does and does not prove

- It proves, on this host, the **mechanism**: `renameTo` over an existing file
  returns false and leaves the old content, and the separator-sensitive
  `endsWith` returns false. That matches the failure messages of the 5
  `MemoryStoreTest` cases and the 1 `AgentSkillCatalogTest` case.
- It does **not** prove anything about a Linux/POSIX JVM (where `renameTo`
  replaces and `File.separator` is `/`), and it is not a full test run. The
  full-suite numbers live in `../pr4-197dd90-junit-summary.txt` and
  `../pr4-a6a498f-junit-summary.txt`.
- Both summaries were path-masked by `../sanitize_evidence.py`, which prints the
  recomputed per-class totals after masking (271/2528/6/0/8 and
  271/2529/0/0/8 — unchanged).
