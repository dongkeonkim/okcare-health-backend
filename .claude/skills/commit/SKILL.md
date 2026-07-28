---
name: 커밋
description: Git_작업_가이드 규칙에 따라 변경을 검토하고 승인된 단위로 커밋한다. 사용자가 커밋을 명시적으로 요청했을 때만 사용한다.
allowed-tools: Bash(git status:*) Bash(git diff:*) Bash(git log:*) Bash(git config:*)
---

# 변경 검토와 커밋

## 현재 상태

```!
git status --short
```

```!
git config user.name; git config user.email
```

## 기준 문서

- 커밋 승인, 단위, 메시지 형식과 커밋 후 보고: [`Git_작업_가이드.md`](../../../docs/운영/Git_작업_가이드.md) 전체
- 커밋 경계와 예상 커밋 메시지: [`개발_계획.md`](../../../docs/설계/개발_계획.md) §12
- 완료 보고 형식: [`작업_계획_가이드.md`](../../../docs/운영/작업_계획_가이드.md) §4

## 자동 강제

`git add`, `git commit`, `git push`는 [`settings.json`](../../settings.json)의 권한 설정으로 실행 전 사용자 확인을 거친다. 커밋 직전 `secret-scan` 훅이 staged 변경을 검사하고 자격 증명이 발견되면 커밋을 차단한다. `includeCoAuthoredBy: false`가 `Co-Authored-By` 트레일러를 끈다.
