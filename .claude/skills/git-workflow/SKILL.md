---
name: git-workflow
description: 브랜치 전략, 커밋 컨벤션, 머지 vs 리베이스, 충돌 해결, 협업 개발 모범 사례를 포함하는 Git 워크플로 패턴. 모든 규모의 팀에 적용 가능하다.
origin: ECC
---

# Git Workflow Patterns

Git 버전 관리, 브랜치 전략, 협업 개발을 위한 모범 사례.

## 호출 시점

- 새 프로젝트의 Git 워크플로 구성
- 브랜치 전략 결정 (GitFlow, Trunk-Based, GitHub Flow)
- 커밋 메시지·PR 설명 작성
- 머지 충돌 해결
- 릴리스 및 버전 태그 관리
- 신규 팀원 Git 사용 온보딩

## 브랜치 전략

### GitHub Flow (단순, 대부분의 팀에 권장)

연속 배포(continuous deployment) 환경과 중소 규모 팀에 적합.

```
main (보호됨, 항상 배포 가능)
  │
  ├── feature/user-auth      → PR → main으로 머지
  ├── feature/payment-flow   → PR → main으로 머지
  └── fix/login-bug          → PR → main으로 머지
```

**규칙:**
- `main`은 항상 배포 가능한 상태를 유지한다
- `main`에서 feature 브랜치를 분기한다
- 리뷰 준비가 되면 Pull Request를 연다
- 승인 + CI 통과 후 `main`에 머지한다
- 머지 직후 즉시 배포한다

### Trunk-Based Development (고속 개발 팀)

강력한 CI/CD와 피처 플래그를 갖춘 팀에 적합.

```
main (트렁크)
  │
  ├── 단명 feature 브랜치 (최대 1~2일)
  ├── 단명 feature 브랜치
  └── 단명 feature 브랜치
```

**규칙:**
- 모두가 `main` 또는 매우 짧은 브랜치에 커밋한다
- 미완성 작업은 피처 플래그로 숨긴다
- 머지 전에 CI가 반드시 통과해야 한다
- 하루에도 여러 번 배포한다

### GitFlow (복잡, 릴리스 사이클 중심)

정해진 릴리스 일정과 엔터프라이즈 프로젝트에 적합.

```
main (운영 릴리스)
  │
  └── develop (통합 브랜치)
        │
        ├── feature/user-auth
        ├── feature/payment
        │
        ├── release/1.0.0    → main과 develop에 머지
        │
        └── hotfix/critical  → main과 develop에 머지
```

**규칙:**
- `main`은 운영 배포 가능한 코드만 포함한다
- `develop`은 통합 브랜치다
- feature 브랜치는 `develop`에서 분기해 `develop`으로 머지한다
- release 브랜치는 `develop`에서 분기해 `main`과 `develop`으로 머지한다
- hotfix 브랜치는 `main`에서 분기해 `main`과 `develop` 양쪽에 머지한다

### 어떤 전략을 언제 사용하나

| 전략 | 팀 규모 | 릴리스 주기 | 적합한 환경 |
|------|---------|-------------|-------------|
| GitHub Flow | 모든 규모 | 연속 배포 | SaaS, 웹 앱, 스타트업 |
| Trunk-Based | 숙련 5명 이상 | 하루 여러 번 | 고속 팀, 피처 플래그 활용 |
| GitFlow | 10명 이상 | 정해진 일정 | 엔터프라이즈, 규제 산업 |

## 커밋 메시지

### Conventional Commits 형식

```
<type>(<scope>): <subject>

[선택: 본문]

[선택: 푸터]
```

### 타입

| 타입 | 사용 시점 | 예시 |
|------|-----------|------|
| `feat` | 새 기능 | `feat(auth): add OAuth2 login` |
| `fix` | 버그 수정 | `fix(api): handle null response in user endpoint` |
| `docs` | 문서 | `docs(readme): update installation instructions` |
| `style` | 포매팅, 코드 변경 없음 | `style: fix indentation in login component` |
| `refactor` | 리팩터링 | `refactor(db): extract connection pool to module` |
| `test` | 테스트 추가/수정 | `test(auth): add unit tests for token validation` |
| `chore` | 유지보수 작업 | `chore(deps): update dependencies` |
| `perf` | 성능 개선 | `perf(query): add index to users table` |
| `ci` | CI/CD 변경 | `ci: add PostgreSQL service to test workflow` |
| `revert` | 이전 커밋 되돌리기 | `revert: revert "feat(auth): add OAuth2 login"` |

### 좋은 예 vs 나쁜 예

```
# BAD: 모호하고 맥락이 없음
git commit -m "fixed stuff"
git commit -m "updates"
git commit -m "WIP"

# GOOD: 명확하고 구체적이며 이유까지 설명
git commit -m "fix(api): retry requests on 503 Service Unavailable

The external API occasionally returns 503 errors during peak hours.
Added exponential backoff retry logic with max 3 attempts.

Closes #123"
```

### 커밋 메시지 템플릿

저장소 루트에 `.gitmessage`를 만든다:

```
# <type>(<scope>): <subject>
# # Types: feat, fix, docs, style, refactor, test, chore, perf, ci, revert
# Scope: api, ui, db, auth, etc.
# Subject: imperative mood, no period, max 50 chars
#
# [optional body] - explain why, not what
# [optional footer] - Breaking changes, closes #issue
```

활성화: `git config commit.template .gitmessage`

## 머지 vs 리베이스

### 머지 (이력 보존)

```bash
# 머지 커밋을 생성한다
git checkout main
git merge feature/user-auth

# 결과:
# *   merge commit
# |\
# | * feature commits
# |/
# * main commits
```

**사용 시점:**
- feature 브랜치를 `main`에 머지할 때
- 정확한 이력을 보존하고 싶을 때
- 여러 명이 같은 브랜치에서 작업한 경우
- 브랜치가 push되어 다른 사람이 그 위에서 작업했을 가능성이 있는 경우

### 리베이스 (선형 이력)

```bash
# feature 커밋을 대상 브랜치 위로 다시 작성한다
git checkout feature/user-auth
git rebase main

# 결과:
# * feature commits (재작성됨)
# * main commits
```

**사용 시점:**
- 로컬 feature 브랜치를 최신 `main`으로 업데이트할 때
- 깔끔한 선형 이력을 원할 때
- 브랜치가 로컬에만 있는 경우 (push되지 않음)
- 본인 외에 작업하는 사람이 없는 경우

### 리베이스 워크플로

```bash
# PR 전에 feature 브랜치를 최신 main으로 업데이트
git checkout feature/user-auth
git fetch origin
git rebase origin/main

# 충돌이 있으면 해결한다
# 테스트는 여전히 통과해야 한다

# 강제 push (본인이 유일한 기여자일 때만)
git push --force-with-lease origin feature/user-auth
```

### 리베이스를 하지 말아야 할 때

```
# 다음 브랜치는 절대 리베이스하지 않는다:
- 공유 저장소에 push된 브랜치
- 다른 사람이 그 위에서 작업한 브랜치
- 보호 브랜치(main, develop)
- 이미 머지된 브랜치

# 이유: 리베이스는 이력을 재작성하므로 다른 사람의 작업을 깨뜨린다
```

## Pull Request 워크플로

### PR 제목 형식

```
<type>(<scope>): <description>

예시:
feat(auth): add SSO support for enterprise users
fix(api): resolve race condition in order processing
docs(api): add OpenAPI specification for v2 endpoints
```

### PR 설명 템플릿

```markdown
## What

이 PR이 무엇을 하는지 간단히 설명.

## Why

동기와 맥락 설명.

## How

강조할 만한 핵심 구현 디테일.

## Testing

- [ ] 단위 테스트 추가/수정
- [ ] 통합 테스트 추가/수정
- [ ] 수동 테스트 수행

## Screenshots (해당 시)

UI 변경의 전후 스크린샷.

## Checklist

- [ ] 코드가 프로젝트 스타일 가이드를 따른다
- [ ] 셀프 리뷰 완료
- [ ] 복잡한 로직에 주석 추가
- [ ] 문서 업데이트
- [ ] 새로운 경고 없음
- [ ] 로컬 테스트 통과
- [ ] 관련 이슈 링크

Closes #123
```

### 코드 리뷰 체크리스트

**리뷰어:**

- [ ] 코드가 명시된 문제를 해결하는가?
- [ ] 처리되지 않은 엣지 케이스가 있는가?
- [ ] 코드가 가독성·유지보수성이 좋은가?
- [ ] 충분한 테스트가 있는가?
- [ ] 보안상 우려는 없는가?
- [ ] 커밋 이력이 깔끔한가? (필요 시 squash)

**작성자:**

- [ ] 리뷰 요청 전 셀프 리뷰 완료
- [ ] CI 통과 (테스트, 린트, 타입체크)
- [ ] PR 크기 적정 (이상적으로 500줄 미만)
- [ ] 단일 기능/수정에 집중
- [ ] 설명이 변경 내용을 명확히 설명

## 충돌 해결

### 충돌 식별

```bash
# 머지 전에 충돌 확인
git checkout main
git merge feature/user-auth --no-commit --no-ff

# 충돌이 있으면 Git이 다음과 같이 표시한다:
# CONFLICT (content): Merge conflict in src/auth/login.ts
# Automatic merge failed; fix conflicts and then commit the result.
```

### 충돌 해결

```bash
# 충돌 파일 확인
git status

# 파일 내 충돌 마커
# <<<<<<< HEAD
# main의 내용
# =======
# feature 브랜치의 내용
# >>>>>>> feature/user-auth

# 옵션 1: 수동 해결
# 파일을 편집해 마커를 제거하고 올바른 내용을 남긴다

# 옵션 2: 머지 도구 사용
git mergetool

# 옵션 3: 한쪽을 통째로 채택
git checkout --ours src/auth/login.ts    # main 버전 유지
git checkout --theirs src/auth/login.ts  # feature 버전 유지

# 해결 후 스테이징 및 커밋
git add src/auth/login.ts
git commit
```

### 충돌 예방 전략

```bash
# 1. feature 브랜치를 작고 짧게 유지
# 2. main으로 자주 리베이스
git checkout feature/user-auth
git fetch origin
git rebase origin/main

# 3. 공유 파일을 건드릴 때 팀과 소통
# 4. 장수명 브랜치 대신 피처 플래그 사용
# 5. PR을 빠르게 리뷰·머지
```

## 브랜치 관리

### 네이밍 컨벤션

```
# Feature 브랜치
feature/user-authentication
feature/JIRA-123-payment-integration

# 버그 수정
fix/login-redirect-loop
fix/456-null-pointer-exception

# 핫픽스 (운영 이슈)
hotfix/critical-security-patch
hotfix/database-connection-leak

# 릴리스
release/1.2.0
release/2024-01-hotfix

# 실험/PoC
experiment/new-caching-strategy
poc/graphql-migration
```

### 브랜치 정리

```bash
# 머지된 로컬 브랜치 삭제
git branch --merged main | grep -v "^\*\|main" | xargs -n 1 git branch -d

# 삭제된 원격 브랜치의 추적 참조 정리
git fetch -p

# 로컬 브랜치 삭제
git branch -d feature/user-auth  # 안전 삭제 (머지된 경우만)
git branch -D feature/user-auth  # 강제 삭제

# 원격 브랜치 삭제
git push origin --delete feature/user-auth
```

### Stash 워크플로

```bash
# 진행 중인 작업 저장
git stash push -m "WIP: user authentication"

# stash 목록
git stash list

# 가장 최근 stash 적용
git stash pop

# 특정 stash 적용
git stash apply stash@{2}

# stash 삭제
git stash drop stash@{0}
```

## 릴리스 관리

### 시맨틱 버저닝(Semantic Versioning)

```
MAJOR.MINOR.PATCH

MAJOR: 호환되지 않는 변경
MINOR: 하위 호환되는 새 기능
PATCH: 하위 호환되는 버그 수정

예시:
1.0.0 → 1.0.1 (patch: 버그 수정)
1.0.1 → 1.1.0 (minor: 새 기능)
1.1.0 → 2.0.0 (major: 호환성 깨짐)
```

### 릴리스 생성

```bash
# 주석이 포함된 태그 생성
git tag -a v1.2.0 -m "Release v1.2.0

Features:
- Add user authentication
- Implement password reset

Fixes:
- Resolve login redirect issue

Breaking Changes:
- None"

# 태그를 원격에 push
git push origin v1.2.0

# 태그 목록
git tag -l

# 태그 삭제
git tag -d v1.2.0
git push origin --delete v1.2.0
```

### Changelog 생성

```bash
# 커밋에서 changelog 생성
git log v1.1.0..v1.2.0 --oneline --no-merges

# 또는 conventional-changelog 사용
npx conventional-changelog -i CHANGELOG.md -s
```

## Git 설정

### 필수 설정

```bash
# 사용자 정보
git config --global user.name "Your Name"
git config --global user.email "your@email.com"

# 기본 브랜치 이름
git config --global init.defaultBranch main

# pull 동작 (merge 대신 rebase)
git config --global pull.rebase true

# push 동작 (현재 브랜치만 push)
git config --global push.default current

# 오타 자동 교정
git config --global help.autocorrect 1

# 더 나은 diff 알고리즘
git config --global diff.algorithm histogram

# 컬러 출력
git config --global color.ui auto
```

### 유용한 alias

```bash
# ~/.gitconfig에 추가
[alias]
    co = checkout
    br = branch
    ci = commit
    st = status
    unstage = reset HEAD --
    last = log -1 HEAD
    visual = log --oneline --graph --all
    amend = commit --amend --no-edit
    wip = commit -m "WIP"
    undo = reset --soft HEAD~1
    contributors = shortlog -sn
```

### Gitignore 패턴

```gitignore
# 의존성
node_modules/
vendor/

# 빌드 산출물
dist/
build/
*.o
*.exe

# 환경 파일
.env
.env.local
.env.*.local

# IDE
.idea/
.vscode/
*.swp
*.swo

# OS 파일
.DS_Store
Thumbs.db

# 로그
*.log
logs/

# 테스트 커버리지
coverage/

# 캐시
.cache/
*.tsbuildinfo
```

## 자주 쓰는 워크플로

### 새 기능 시작

```bash
# 1. main 브랜치 최신화
git checkout main
git pull origin main

# 2. feature 브랜치 생성
git checkout -b feature/user-auth

# 3. 변경 후 커밋
git add .
git commit -m "feat(auth): implement OAuth2 login"

# 4. 원격 push
git push -u origin feature/user-auth

# 5. GitHub/GitLab에서 Pull Request 생성
```

### 신규 변경으로 PR 업데이트

```bash
# 1. 추가 변경
git add .
git commit -m "feat(auth): add error handling"

# 2. 업데이트 push
git push origin feature/user-auth
```

### 포크와 upstream 동기화

```bash
# 1. upstream 원격 추가 (한 번만)
git remote add upstream https://github.com/original/repo.git

# 2. upstream fetch
git fetch upstream

# 3. upstream/main을 본인 main에 머지
git checkout main
git merge upstream/main

# 4. 본인 fork로 push
git push origin main
```

### 실수 되돌리기

```bash
# 마지막 커밋 취소 (변경 유지)
git reset --soft HEAD~1

# 마지막 커밋 취소 (변경 폐기)
git reset --hard HEAD~1

# 원격에 push된 마지막 커밋 되돌리기
git revert HEAD
git push origin main

# 특정 파일 변경 되돌리기
git checkout HEAD -- path/to/file

# 마지막 커밋 메시지 수정
git commit --amend -m "New message"

# 빠뜨린 파일을 마지막 커밋에 추가
git add forgotten-file
git commit --amend --no-edit
```

## Git Hooks

### Pre-Commit 훅

```bash
#!/bin/bash
# .git/hooks/pre-commit

# 린트 실행
npm run lint || exit 1

# 테스트 실행
npm test || exit 1

# 시크릿 검사
if git diff --cached | grep -E '(password|api_key|secret)'; then
    echo "Possible secret detected. Commit aborted."
    exit 1
fi
```

### Pre-Push 훅

```bash
#!/bin/bash
# .git/hooks/pre-push

# 전체 테스트 실행
npm run test:all || exit 1

# console.log 검출
if git diff origin/main | grep -E 'console\.log'; then
    echo "Remove console.log statements before pushing."
    exit 1
fi
```

## 안티패턴

```
# BAD: main에 직접 커밋
git checkout main
git commit -m "fix bug"

# GOOD: feature 브랜치 + PR 사용

# BAD: 시크릿 커밋
git add .env  # API 키 포함

# GOOD: .gitignore에 추가, 환경 변수 사용

# BAD: 거대한 PR (1000줄 이상)
# GOOD: 작고 집중된 PR로 분할

# BAD: "Update" 같은 커밋 메시지
git commit -m "update"
git commit -m "fix"

# GOOD: 설명이 있는 메시지
git commit -m "fix(auth): resolve redirect loop after login"

# BAD: 공개된 이력 재작성
git push --force origin main

# GOOD: 공개 브랜치는 revert로 처리
git revert HEAD

# BAD: 장수명 feature 브랜치 (수 주~수 개월)
# GOOD: 브랜치를 짧게(며칠) 유지하고 자주 리베이스

# BAD: 생성 파일 커밋
git add dist/
git add node_modules/

# GOOD: .gitignore에 추가
```

## 빠른 참조

| 작업 | 명령 |
|------|------|
| 브랜치 생성 | `git checkout -b feature/name` |
| 브랜치 전환 | `git checkout branch-name` |
| 브랜치 삭제 | `git branch -d branch-name` |
| 브랜치 머지 | `git merge branch-name` |
| 브랜치 리베이스 | `git rebase main` |
| 이력 조회 | `git log --oneline --graph` |
| 변경사항 보기 | `git diff` |
| 스테이징 | `git add .` 또는 `git add -p` |
| 커밋 | `git commit -m "message"` |
| Push | `git push origin branch-name` |
| Pull | `git pull origin branch-name` |
| Stash | `git stash push -m "message"` |
| 마지막 커밋 취소 | `git reset --soft HEAD~1` |
| 커밋 revert | `git revert HEAD` |
