@AGENTS.md

## Claude Code 전용 규칙

위 [`AGENTS.md`](./AGENTS.md)의 「프로젝트 스킬 라우팅」은 Codex 스킬(`$이름`)을 전제한다. Claude Code는 같은 기준 문서를 아래 방식으로 사용한다.

- 스킬은 `$이름`이 아니라 `/이름`으로 호출한다.
- 경로별 구현 규칙은 [`.claude/rules/`](./.claude/rules)가 해당 경로의 파일을 열 때 자동으로 로드한다. 규칙 본문을 이 문서에 복제하지 않는다.
- 병렬 작업은 [`오케스트레이션_가이드.md`](./docs/운영/오케스트레이션_가이드.md)의 Orca 탭을 사용한다. 스킬의 `context: fork`는 컨텍스트 격리 수단이며 Orca 탭을 대체하지 않는다.
- 커밋 승인, 파괴적 명령과 범위 통제는 `AGENTS.md`의 원칙을 그대로 따른다.

## 작업 유형별 진입점

| 작업 | 진입점 | 기준 문서 |
|---|---|---|
| Java·Spring 구현 | `.claude/rules/java-main.md` (자동 로드) | [`개발_계획.md`](./docs/설계/개발_계획.md) |
| 테스트와 검증 | `.claude/rules/java-test.md` (자동 로드), `/verify-regression` | [`기능_명세.md`](./docs/명세/기능_명세.md) |
| Flyway migration | `.claude/rules/flyway.md` (자동 로드) | [`개발_계획.md`](./docs/설계/개발_계획.md) |
| 문서 작업 | `.claude/rules/docs.md` (자동 로드), `/sync-docs` | [`문서_작성_가이드.md`](./docs/운영/문서_작성_가이드.md) |
| 경계 보고 | `/boundary-report` | [`작업_계획_가이드.md`](./docs/운영/작업_계획_가이드.md) |
| 변경 검토와 커밋 | `/commit` | [`Git_작업_가이드.md`](./docs/운영/Git_작업_가이드.md) |

## 자동으로 강제되는 규칙

다음은 판단에 맡기지 않고 [`.claude/settings.json`](./.claude/settings.json)이 강제한다.

- `git add`, `git commit`, `git push`는 실행 전 사용자 확인을 받는다.
- `.env` 계열 파일은 읽지 않는다.
- 문서를 수정하면 상대 링크와 줄 끝 공백을 검사한다.
- 커밋 직전 staged 변경에서 자격 증명과 키를 검사하고 발견하면 커밋을 차단한다.
