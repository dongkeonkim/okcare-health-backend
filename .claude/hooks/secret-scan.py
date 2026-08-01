#!/usr/bin/env python3
"""커밋 직전 스테이징된 변경에서 자격 증명과 키를 검사하는 PreToolUse 훅.

승인 규칙만으로는 긴 diff의 비밀정보를 검출할 수 없어 별도 검사.
오탐으로 훅 사용이 중단되지 않도록 확실한 패턴만 검사.
일반적인 비밀번호 대입은 예시 요청과 구분하기 어려워 제외.
과제가 제공한 건강 데이터 fixture도 검사 대상에서 제외.
발견 시 커밋 차단.
"""

import json
import re
import subprocess
import sys

CONTENT_PATTERNS = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "개인 키 블록"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS 액세스 키 ID"),
    (re.compile(r"\bghp_[A-Za-z0-9]{36}\b"), "GitHub personal access token"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"), "GitHub fine-grained token"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}"), "Slack 토큰"),
    (re.compile(r"\bsk-[A-Za-z0-9]{32,}\b"), "API 비밀 키"),
    (re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"), "Google API 키"),
]

FILE_PATTERNS = [
    (re.compile(r"(^|/)\.env$"), ".env 파일"),
    (re.compile(r"(^|/)\.env\.(?!example$)[^/]+$"), ".env 파생 파일"),
    (re.compile(r"\.(pem|key|p12|jks|keystore)$"), "키 파일"),
]

MAX_REPORT = 10


def git(args):
    try:
        done = subprocess.run(
            ["git"] + args,
            capture_output=True,
            text=True,
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    return done.stdout


def staged_file_findings():
    listing = git(["diff", "--cached", "--name-only", "--diff-filter=ACMR"])
    if not listing:
        return []

    findings = []
    for path in listing.splitlines():
        path = path.strip()
        if not path:
            continue
        for pattern, label in FILE_PATTERNS:
            if pattern.search(path):
                findings.append("%s (%s)" % (path, label))
                break
    return findings


def staged_content_findings():
    diff = git(["diff", "--cached", "--unified=0"])
    if not diff:
        return []

    findings = []
    current = "(알 수 없는 파일)"
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            continue
        if not line.startswith("+") or line.startswith("+++"):
            continue
        added = line[1:]
        for pattern, label in CONTENT_PATTERNS:
            if pattern.search(added):
                findings.append("%s: %s 추정" % (current, label))
                break
    return findings


def is_commit_command(payload):
    command = (payload.get("tool_input") or {}).get("command") or ""
    return re.search(r"\bgit\s+(-\S+\s+|--\S+(=\S+)?\s+)*commit\b", command) is not None


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, OSError):
        return 0

    if not is_commit_command(payload):
        return 0

    findings = staged_file_findings() + staged_content_findings()
    if not findings:
        return 0

    unique = list(dict.fromkeys(findings))
    print("커밋 차단: 스테이징된 변경에서 자격 증명으로 보이는 내용을 찾았습니다.", file=sys.stderr)
    for finding in unique[:MAX_REPORT]:
        print("  - %s" % finding, file=sys.stderr)
    if len(unique) > MAX_REPORT:
        print("  ... 외 %d건" % (len(unique) - MAX_REPORT), file=sys.stderr)
    print("해당 내용을 제거하거나 환경변수로 옮긴 뒤 다시 커밋하세요.", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
