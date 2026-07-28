#!/usr/bin/env python3
"""문서를 수정한 뒤 상대 링크와 줄 끝 공백을 검사하는 PostToolUse 훅.

기준: docs/운영/문서_작성_가이드.md §3

PostToolUse는 도구가 이미 실행된 뒤에 동작하므로 차단이 아니라 수정 요청 역할을
한다. 문제를 찾으면 exit 2로 stderr 내용을 Claude에게 전달한다.
"""

import json
import os
import re
import sys

# 검사 대상. 이 경로 밖의 마크다운은 건드리지 않는다.
TARGET_PREFIXES = ("docs/", ".claude/")
TARGET_FILES = ("README.md", "AGENTS.md", "CLAUDE.md")

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
HEADING_RE = re.compile(r"^#{1,6}\s+(.*?)\s*$")
SCHEME_RE = re.compile(r"^[a-z][a-z0-9+.-]*:", re.IGNORECASE)

MAX_REPORT = 20


def slugify(heading):
    """GitHub 방식으로 제목을 앵커 슬러그로 바꾼다. 한글은 그대로 남는다."""
    text = heading.strip().lower()
    text = re.sub(r"[`*_~]", "", text)                # 인라인 서식 제거
    text = re.sub(r"[^\w\s-]", "", text)              # 구두점 제거
    return re.sub(r"\s+", "-", text.strip())


def anchors_of(path):
    """마크다운 파일의 제목에서 앵커 슬러그 집합을 만든다."""
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
    """상대 링크가 실제 파일과 앵커를 가리키는지 확인한다."""
    if SCHEME_RE.match(target) or target.startswith("//"):
        return ""                                     # 외부 링크는 검사하지 않는다

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
    """검사 대상이면 저장소 기준 상대 경로를, 아니면 None을 돌려준다."""
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
        return 0                                      # 입력을 읽지 못하면 통과시킨다

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
            continue                                  # 코드 블록 안의 링크는 검사하지 않는다
        for target in LINK_RE.findall(line):
            problem = check_link(file_path, target)
            if problem:
                problems.append("%s:%d %s" % (rel, number, problem))

    if not problems:
        return 0

    print("문서 검사 실패 (문서_작성_가이드 §3):", file=sys.stderr)
    for problem in problems[:MAX_REPORT]:
        print("  - %s" % problem, file=sys.stderr)
    if len(problems) > MAX_REPORT:
        print("  ... 외 %d건" % (len(problems) - MAX_REPORT), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
