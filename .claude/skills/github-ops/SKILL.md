---
name: github-ops
description: GitHub 저장소 운영·자동화·관리. gh CLI를 활용한 이슈 분류, PR 관리, CI/CD 운영, 릴리스 관리, 보안 모니터링. 단순 git 명령을 넘어 GitHub 이슈·PR·CI 상태·릴리스·기여자·stale 항목 관리 등 GitHub 운영 작업을 수행할 때 사용한다.
origin: ECC
---

# GitHub Operations

커뮤니티 건전성, CI 신뢰성, 기여자 경험을 중심으로 GitHub 저장소를 관리한다.

## 호출 시점

- 이슈 분류 (분류, 라벨링, 응답, 중복 제거)
- PR 관리 (리뷰 상태, CI 체크, stale PR, 머지 가능성)
- CI/CD 실패 디버깅
- 릴리스 및 changelog 준비
- Dependabot 및 보안 알림 모니터링
- 오픈소스 프로젝트 기여자 경험 관리
- 사용자가 "GitHub 확인", "이슈 분류", "PR 리뷰", "머지", "릴리스", "CI 깨졌어" 등을 언급할 때

## 도구 요구사항

- 모든 GitHub API 작업에 **gh CLI** 사용
- `gh auth login`으로 저장소 접근 권한 설정 필요

## 이슈 분류

각 이슈를 타입과 우선순위로 분류한다:

**타입:** bug, feature-request, question, documentation, enhancement, duplicate, invalid, good-first-issue

**우선순위:** critical (장애/보안), high (영향 큼), medium (있으면 좋음), low (사소한 문제)

### 분류 워크플로

1. 이슈 제목, 본문, 댓글을 읽는다
2. 기존 이슈와 중복인지 확인한다 (키워드로 검색)
3. `gh issue edit --add-label`로 적절한 라벨을 적용한다
4. 질문 이슈는 도움이 되는 응답을 작성해 게시한다
5. 정보가 부족한 버그 이슈는 재현 절차를 요청한다
6. good first issue에 해당하면 `good-first-issue` 라벨을 추가한다
7. 중복 이슈는 원본 링크를 댓글로 남기고 `duplicate` 라벨을 추가한다

```bash
# 잠재적 중복 검색
gh issue list --search "keyword" --state all --limit 20

# 라벨 추가
gh issue edit <number> --add-label "bug,high-priority"

# 이슈에 댓글 작성
gh issue comment <number> --body "Thanks for reporting. Could you share reproduction steps?"
```

## PR 관리

### 리뷰 체크리스트

1. CI 상태 확인: `gh pr checks <number>`
2. 머지 가능 여부 확인: `gh pr view <number> --json mergeable`
3. PR 생성 후 경과 시간 및 마지막 활동 확인
4. 5일 이상 리뷰가 없는 PR을 표시
5. 커뮤니티 PR은 테스트와 컨벤션 준수 여부 확인

### Stale 정책

- 14일 이상 활동이 없는 이슈: `stale` 라벨 추가, 업데이트 요청 댓글
- 7일 이상 활동이 없는 PR: 진행 여부를 묻는 댓글
- 30일간 응답이 없는 stale 이슈는 자동 종료 (`closed-stale` 라벨 추가)

```bash
# stale 이슈 조회 (14일 이상 비활성)
gh issue list --label "stale" --state open

# 최근 활동이 없는 PR 조회
gh pr list --json number,title,updatedAt --jq '.[] | select(.updatedAt < "2026-03-01")'
```

## CI/CD 운영

CI 실패 시:

1. 워크플로 실행 확인: `gh run view <run-id> --log-failed`
2. 실패한 단계 식별
3. 일시적인(flaky) 테스트인지 실제 실패인지 판단
4. 실제 실패: 근본 원인을 식별하고 수정 방안 제안
5. flaky 테스트: 향후 조사를 위해 패턴을 기록

```bash
# 최근 실패한 실행 조회
gh run list --status failure --limit 10

# 실패한 실행 로그 조회
gh run view <run-id> --log-failed

# 실패한 워크플로 재실행
gh run rerun <run-id> --failed
```

## 릴리스 관리

릴리스 준비 시:

1. main 브랜치의 모든 CI가 그린인지 확인
2. 미릴리스 변경사항 검토: `gh pr list --state merged --base main`
3. PR 제목으로 changelog 생성
4. 릴리스 생성: `gh release create`

```bash
# 마지막 릴리스 이후 머지된 PR 조회
gh pr list --state merged --base main --search "merged:>2026-03-01"

# 릴리스 생성
gh release create v1.2.0 --title "v1.2.0" --generate-notes

# 사전 릴리스(pre-release) 생성
gh release create v1.3.0-rc1 --prerelease --title "v1.3.0 Release Candidate 1"
```

## 보안 모니터링

```bash
# Dependabot 알림 확인
gh api repos/{owner}/{repo}/dependabot/alerts --jq '.[].security_advisory.summary'

# 시크릿 스캐닝 알림 확인
gh api repos/{owner}/{repo}/secret-scanning/alerts --jq '.[].state'

# 안전한 의존성 업데이트 검토 및 자동 머지
gh pr list --label "dependencies" --json number,title
```

- 안전한 의존성 업데이트는 검토 후 자동 머지
- critical/high 등급 알림은 즉시 표시
- 최소 주 1회 신규 Dependabot 알림 확인

## 품질 게이트

GitHub 운영 작업을 완료하기 전에 다음을 확인한다:
- 분류한 모든 이슈에 적절한 라벨이 부여되어 있다
- 7일 이상 리뷰·코멘트가 없는 PR이 없다
- CI 실패는 단순 재실행이 아닌 원인 조사가 이뤄졌다
- 릴리스에는 정확한 changelog가 포함되어 있다
- 보안 알림은 인지·추적되고 있다
