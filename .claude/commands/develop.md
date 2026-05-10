---
description: .claude/plans/[작업명]/ 의 plan 문서들을 입력으로 받아 TDD + 코드리뷰 + 문서화 + 문서리뷰 사이클로 실제 구현을 진행한다.
argument-hint: <작업명>
---

# Develop — TDD + 리뷰 + 문서화 사이클

`$ARGUMENTS`로 받은 작업명에 해당하는 plan 디렉터리를 읽어 5단계 사이클로 구현한다. 각 단계는 전용 agent가 담당하며, 단계 사이마다 사용자 확인 게이트를 거친다.

## 진행 순서

### 0. 사전 점검

`$ARGUMENTS`가 비어 있으면 `ls .claude/plans/`로 작업 디렉터리 목록을 보여주고 사용자에게 어떤 작업을 develop할지 묻는다.

작업명을 확정한 후:

```bash
ls .claude/plans/[작업명]/
git status --short
git branch --show-current
```

확인 사항:
- `.claude/plans/[작업명]/` 안에 `todo.md`, `api-design-plan.md`, `implementation-plan.md`, `db-design-plan.md` 4개 모두 있는가
- 작업 트리가 dirty이면 사용자에게 경고 (계속할지 stash할지 묻기)
- 빌드/테스트 명령 학습 (`build.gradle*`, `pom.xml`, `package.json`, `pyproject.toml`, `Cargo.toml`, `go.mod` 등)

확인 결과를 사용자에게 보고하고 진행 동의를 받는다.

---

### 1. test 단계

**호출**: `test-writer` agent

**전달 컨텍스트**:
- 작업명
- plan 디렉터리 경로 (`.claude/plans/[작업명]/`)

**기대 결과**:
- 테스트 파일 작성
- 작성한 테스트 목록과 실패 양상 보고

**메인의 검증**:
- 빌드/테스트 명령 실행
- ✅ 모든 새 테스트가 실패 또는 컴파일 에러 (Red)
- ❌ 통과해버리는 테스트가 있으면 → test-writer 재호출 (테스트가 너무 약함)
- 기존 테스트는 모두 통과 유지 (망가뜨렸으면 즉시 수정)

**사용자 확인 게이트**:
> "테스트 N개 작성 완료, Red 확인. 다음 단계(implementation)로 진행할까요?"

---

### 2. implementation 단계

**호출**: `implementer` agent (모드 A)

**전달 컨텍스트**:
- 작업명
- plan 디렉터리 경로
- 작성된 테스트 파일 목록

**기대 결과**:
- plan 문서대로 구현
- Green 보고

**메인의 검증**:
- 빌드/테스트 명령 실행
- ✅ 모든 테스트 통과 (Green)
- ❌ 실패하면 → implementer 재호출 (몇 사이클 반복)
- ❌ implementer가 "plan과 충돌해 진행 불가" 리턴하면 → 사용자에게 plan 수정 또는 진행 방향 묻기

**사용자 확인 게이트**:
> "구현 완료, Green 확인. 코드리뷰 단계로 진행할까요?"

---

### 3. code-review 단계

**호출**: `code-reviewer` agent

**전달 컨텍스트**:
- 작업명
- plan 디렉터리 경로
- 변경된 파일 목록 (`git diff --name-only`)

**기대 결과**:
- 리뷰 보고서 (반영 권장 / 참고 분류)

**반영 절차**:
- 반영 권장 항목이 있으면 사용자에게 보여주고 어떤 항목을 반영할지 선택받는다 (`AskUserQuestion`)
- 선택된 항목을 `implementer` agent에 모드 B로 전달
- 반영 후 빌드/테스트 다시 실행 → Green 유지 확인
- 반영해도 새 리뷰가 발견되면 한 사이클 더. 무한 루프 방지를 위해 최대 2회만.

**사용자 확인 게이트**:
> "리뷰 반영 완료, Green 유지. 문서화 단계로 진행할까요?"

---

### 4. docs 단계

**호출**: `docs-writer` agent (모드 A)

**전달 컨텍스트**:
- 작업명
- plan 디렉터리 경로
- 변경된 파일 목록

**기대 결과**:
- `docs/` 업데이트
- 변경한 docs 파일 목록 보고

**사용자 확인 게이트**:
> "문서 N개 갱신. 문서리뷰 단계로 진행할까요?"

---

### 5. doc-review 단계

**호출**: `doc-reviewer` agent

**전달 컨텍스트**:
- 작업명
- 변경된 docs 파일 목록
- (참고) plan 디렉터리 경로

**기대 결과**:
- 리뷰 보고서

**반영 절차**:
- 반영 권장 항목을 사용자에게 보여주고 선택받는다
- 선택된 항목을 `docs-writer` agent에 모드 B로 전달
- 최대 2회 반복

**사용자 확인 게이트**:
> "문서리뷰 반영 완료. 최종 보고서를 작성할까요?"

---

### 6. 결과 보고

`.claude/plans/[작업명]/report.md`를 작성한다:

````markdown
# [작업명] - report

## 메타
- **작업명**: [작업명]
- **작성일**: YYYY-MM-DD
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 진행 결과

### 1. test
- 작성된 테스트 파일: N개
- Red 확인 / Green 전환 결과

### 2. implementation
- 변경된 파일: 코드 X / 테스트 Y
- 빌드/테스트 결과

### 3. code-review
- 리뷰 항목: 반영 A / 무시 B / 참고 C
- 재검증 결과

### 4. docs
- 갱신된 docs 파일: N개

### 5. doc-review
- 리뷰 항목: 반영 A / 무시 B / 참고 C

## 변경 요약
- 신규 파일: ...
- 수정 파일: ...
- 갱신 docs: ...

## plan과의 차이
{있다면 — 구현 도중 발견된 plan 수정 필요 항목 등}

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
````

사용자에게 최종 보고:

````
## Develop 완료

작업명: {작업명}

| 단계 | 결과 |
|------|------|
| test  | N개 작성, Red 확인 |
| implementation | M개 변경, Green |
| code-review | 반영 A / 참고 B |
| docs | N개 갱신 |
| doc-review | 반영 A / 참고 B |

변경 파일 합계: 코드 X / 테스트 Y / 문서 Z
보고서: .claude/plans/{작업명}/report.md

다음 단계:
- /commit 으로 커밋
- /git-pr 로 PR 생성
````

---

## 주의

- **각 단계 사이의 사용자 확인 게이트는 생략하지 않는다** — 자동 진행은 위험
- **agent는 자기 책임만 수행** — code-reviewer/doc-reviewer는 절대 수정하지 않는다
- **반영은 implementer/docs-writer 재호출로** — 메인이 직접 수정하지 않는다
- **무한 루프 방지** — 리뷰 반영 사이클은 최대 2회
- **브랜치/커밋은 사용자에게 맡긴다** — develop은 코드 변경만, 커밋은 `/commit`, PR은 `/git-pr`
