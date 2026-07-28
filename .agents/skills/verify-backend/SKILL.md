---
name: verify-backend
description: 오케어 백엔드 테스트 실행과 검증을 기능 계약, 테스트 전략, 회귀 입력과 결과 보고 기준으로 라우팅한다. 단위, MVC 슬라이스, Testcontainers 통합, fixture 회귀, E2E, 전체 빌드, Docker Compose 스모크 실행, 실패 분석과 검증 누락 점검에 사용한다.
---

# 백엔드 검증 라우팅

- 공통 원칙: [`AGENTS.md`](../../../AGENTS.md)
- 제품 범위: [`비즈니스_기획.md`](../../../docs/요구사항/비즈니스_기획.md)
- 기대 동작과 회귀값: [`기능_명세.md`](../../../docs/명세/기능_명세.md)
- 테스트 전략과 실행 환경: [`개발_계획.md`](../../../docs/설계/개발_계획.md)
- 리뷰 경계와 결과 보고: [`작업_계획_가이드.md`](../../../docs/운영/작업_계획_가이드.md)
- 회귀 입력: [`fixtures/health`](../../../fixtures/health)
- 테스트 코드 설계, 작성, 수정과 리뷰: `$write-backend-tests`
- 관련 문서 변경: `$maintain-project-docs`
- Git 검토와 커밋: `$manage-git-commits`
