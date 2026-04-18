---
name: git-branch
description: 이 스킬은 사용자가 "브랜치 생성해줘", "브랜치 만들어줘", "새 브랜치", "git branch 생성", "feat 브랜치", "/git-branch" 등을 요청하거나 새 작업을 시작하기 위해 브랜치 생성이 필요할 때 호출된다. 현재 작업 상태를 점검하고, 프로젝트 네이밍 규칙에 맞는 브랜치를 생성·전환한다.
---

# Git Branch 생성

auth-common 프로젝트에서 새 브랜치를 생성한다.

## 동작 순서

### 1. 현재 상태 점검

아래 명령을 병렬 실행한다:

- `git status --short` — 작업 디렉터리 상태
- `git branch --show-current` — 현재 브랜치
- `git symbolic-ref refs/remotes/origin/HEAD --short` — 원격 기본 브랜치 (보통 `origin/main`)
- `git fetch origin` — 원격 최신 상태 (실패해도 중단하지 않음)

### 2. 브랜치 정보 수집

사용자에게 아래를 물어본다 (이미 제공된 경우 생략):

1. **타입**: `feat` / `fix` / `refactor` / `docs` / `chore` / `test` / `style`
2. **주제**: 영문 kebab-case로 간결하게 (예: `passport-argument-resolver`)

브랜치명 형식: `{타입}/{주제}` (예: `feat/passport-argument-resolver`)

### 3. 사전 체크

다음 항목을 점검하고 문제가 있으면 사용자에게 알리고 선택을 받는다:

- **Uncommitted changes 존재**: "변경사항이 있습니다. 1) 스태시 후 진행 2) 그대로 진행 3) 취소"
- **현재 브랜치가 기본 브랜치가 아님**: "현재 `{branch}`에서 분기합니다. 1) 그대로 진행 2) `main` 기준으로 생성 3) 취소"
- **동일한 이름의 브랜치가 로컬 또는 원격에 이미 존재**: "`{name}` 브랜치가 이미 존재합니다. 다른 이름을 입력해 주세요."

### 4. 생성 확인

최종 정보를 보여주고 확인받는다:

```
생성할 브랜치: feat/passport-argument-resolver
베이스:       main (origin/main과 동기화됨)
uncommitted:  없음

1) 생성
2) 이름 변경
3) 취소
```

### 5. 브랜치 생성

확인 후 아래를 실행한다:

- 베이스를 최신화하는 경우: `git checkout main && git pull --ff-only origin main`
- 브랜치 생성 및 전환: `git checkout -b {branch-name}`

완료 후 `git status --short --branch` 결과를 간단히 보고한다.

## 엣지 케이스

- **fetch 실패 (오프라인)**: 로컬 기본 브랜치 기준으로 진행하고 "원격 확인 건너뜀" 고지.
- **기본 브랜치 감지 실패**: `main` → `master` 순으로 시도, 둘 다 없으면 사용자에게 질문.
- **`git pull --ff-only` 실패 (divergence)**: 자동 해결하지 않고 사용자에게 알린 뒤 중단.

## 금지 사항

- 사용자 확인 없이 `git stash`, `git reset --hard`, `git push --force`, `git branch -D` 등 파괴적 명령을 실행하지 않는다.
- 브랜치명을 한글로 만들지 않는다.
- 생성 직후 원격으로 push하지 않는다 (별도 요청이 있을 때만).
- 기본 브랜치(`main`)에서 직접 작업을 권유하지 않는다.
