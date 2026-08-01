#!/usr/bin/env python3
"""문서 수정 후 상대 링크와 줄 끝 공백을 검사하는 PostToolUse 훅.

PostToolUse는 도구 실행 후에 동작하므로 차단 대신 표준 오류로 수정 요청 전달.
"""

import json
import os
import re
import sys

TARGET_PREFIXES = ("docs/", ".claude/")
TARGET_FILES = ("README.md", "AGENTS.md", "CLAUDE.md")

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
HEADING_RE = re.compile(r"^#{1,6}\s+(.*?)\s*$")
SCHEME_RE = re.compile(r"^[a-z][a-z0-9+.-]*:", re.IGNORECASE)

MAX_REPORT = 20


def slugify(heading):
    text = heading.strip().lower()
    text = re.sub(r"[`*_~]", "", text)
    text = re.sub(r"[^\w\s-]", "", text)
    return re.sub(r"\s+", "-", text.strip())


def anchors_of(path):
    try:
        with open(path, encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return set()

    anchors = set()
    fenced = False
    for line in lines:
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        matched = HEADING_RE.match(line)
        if matched:
            anchors.add(slugify(matched.group(1)))
    return anchors


def check_link(source, target):
    if SCHEME_RE.match(target) or target.startswith("//"):
        return ""

    path_part, _, anchor = target.partition("#")
    if path_part:
        resolved = os.path.normpath(os.path.join(os.path.dirname(source), path_part))
        if not os.path.exists(resolved):
            return "링크 대상 없음 -> %s" % target
    else:
        resolved = source

    if anchor and resolved.endswith(".md"):
        if anchor.lower() not in anchors_of(resolved):
            return "앵커 없음 -> %s" % target
    return ""


def target_relpath(file_path):
    if not file_path or not file_path.endswith(".md"):
        return None
    if not os.path.isfile(file_path):
        return None

    project = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    try:
        rel = os.path.relpath(file_path, project)
    except ValueError:
        return None

    if rel.startswith(".."):
        return None
    if rel.startswith(TARGET_PREFIXES) or rel in TARGET_FILES:
        return rel
    return None


def main():
    try:
        payload = json.load(sys.stdin)
    except (ValueError, OSError):
        return 0  # 훅 장애가 문서 편집을 막지 않도록 검사 생략.

    file_path = (payload.get("tool_input") or {}).get("file_path")
    rel = target_relpath(file_path)
    if rel is None:
        return 0

    with open(file_path, encoding="utf-8") as handle:
        lines = handle.read().splitlines()

    problems = []
    fenced = False
    for number, line in enumerate(lines, 1):
        if line.lstrip().startswith("```"):
            fenced = not fenced
        if line != line.rstrip():
            problems.append("%s:%d 줄 끝 공백" % (rel, number))
        if fenced:
            continue
        for target in LINK_RE.findall(line):
            problem = check_link(file_path, target)
            if problem:
                problems.append("%s:%d %s" % (rel, number, problem))

    if not problems:
        return 0

    print("문서 검사 실패:", file=sys.stderr)
    for problem in problems[:MAX_REPORT]:
        print("  - %s" % problem, file=sys.stderr)
    if len(problems) > MAX_REPORT:
        print("  ... 외 %d건" % (len(problems) - MAX_REPORT), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
