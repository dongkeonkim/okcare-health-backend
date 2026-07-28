#!/usr/bin/env python3
"""커밋 직전 staged 변경에서 자격 증명과 키를 검사하는 PreToolUse 훅.

기준: AGENTS.md 「항상 적용하는 원칙」, docs/운영/Git_작업_가이드.md §2

권한 설정의 ask 규칙은 사용자 승인을 받을 뿐이고, 긴 diff에서 사람이 secret을
찾아내지는 못한다. 실패 모드가 다르므로 훅으로 따로 막는다.

오탐이 잦으면 훅을 꺼버리게 되므로 확실한 패턴만 검사한다. 일반적인
`password: "..."` 대입은 기능_명세의 예시 요청과 구분할 수 없어 제외했다.
fixtures/health의 건강 데이터는 과제가 제공한 회귀 입력이므로 대상이 아니다.

발견하면 exit 2로 커밋을 차단한다.
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
    """git 명령을 실행하고 표준 출력을 돌려준다. 실패하면 None."""
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
    """staged 파일 이름 자체가 위험한 경우를 찾는다."""
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
    """staged diff에서 추가된 줄만 검사한다."""
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
    """git commit 호출인지 확인한다. if 필터가 이미 걸러주지만 한 번 더 본다."""
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
    print("커밋 차단: staged 변경에서 자격 증명으로 보이는 내용을 찾았습니다.", file=sys.stderr)
    for finding in unique[:MAX_REPORT]:
        print("  - %s" % finding, file=sys.stderr)
    if len(unique) > MAX_REPORT:
        print("  ... 외 %d건" % (len(unique) - MAX_REPORT), file=sys.stderr)
    print("해당 내용을 제거하거나 환경변수로 옮긴 뒤 다시 커밋하세요.", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
