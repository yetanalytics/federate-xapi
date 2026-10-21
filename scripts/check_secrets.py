#!/usr/bin/env python3
"""Guard tracked sources against committed secret literals.

This is defense-in-depth. It does not scan Git history, ignored files, images,
logs, or external credential stores; never print suspected secret values.
"""
import re
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
tracked = [p.decode("utf-8", "surrogateescape") for p in
           subprocess.check_output(["git", "ls-files", "-z"], cwd=root).split(b"\0") if p]
patterns = (
    re.compile(r"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
    re.compile(r"\b(?:github_pat_|gh[pousr]_|xox[baprs]-|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,})"),
    re.compile(r"""(?i)(?:password|secret|api[_-]?key|access[_-]?token|bearer[_-]?token)\s*[:=]\s*['"][^'"$]{4,}['"]"""),
    re.compile(r"(?i)^\s*-?\s*(?:POSTGRES_PASSWORD|LRSQL_API_KEY_DEFAULT|LRSQL_API_SECRET_DEFAULT|LRSQL_ADMIN_PASS_DEFAULT|LRSQL_DB_PASSWORD|ARTEMIS_PASSWORD)\s*[:=]\s*(?!\$\{)[^\s]+"),
)
findings = []
for name in tracked:
    if name == "scripts/check_secrets.py":
        continue
    path = root / name
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        continue
    for index, line in enumerate(lines, 1):
        if any(pattern.search(line) for pattern in patterns):
            findings.append(f"{name}:{index}: possible embedded credential")
for finding in findings:
    print(finding, file=sys.stderr)
if findings:
    sys.exit(f"FAIL: {len(findings)} possible credentials; inspect locally and remove.")
print(f"PASS: checked {len(tracked)} tracked paths for common embedded credential patterns.")
