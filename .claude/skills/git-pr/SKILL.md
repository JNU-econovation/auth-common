---
name: git-pr
description: 이 스킬은 사용자가 "PR 생성해줘", "PR 만들어줘", "pull request 생성", "pr 올려줘", "/git-pr" 등을 요청할 때 호출된다. 현재 브랜치의 커밋을 원격으로 push하고, `.github/PULL_REQUEST_TEMPLATE.md`를 채워 GitHub Pull Request를 생성한다.
---

# Git PR 생성

현재 브랜치 작업을 원격에 push하고 GitHub PR을 생성한다.
PR 본문 구조는 `.github/PULL_REQUEST_TEMPLATE.md`를 단일 진실 소스로 사용한다.

## 사전 요구사항

- `gh` CLI가 설치·인증되어 있어야 한다 (`gh auth status`).
- 원격이 GitHub 저장소여야 한다 (`origin` 기준).
- `.github/PULL_REQUEST_TEMPLATE.md`가 존재해야 한다.

## 동작 순서

### 1. 현재 상태 점검

병렬 실행:

- `git branch --show-current`
- `git status --short`
- `git symbolic-ref refs/remotes/origin/HEAD --short`
- `git log --oneline {base}..HEAD`
- `gh pr view --json number,url,state 2>/dev/null`

### 2. 사전 검증

- **기본 브랜치에서 직접 호출됨**: 고지 후 중단.
- **uncommitted 변경사항 존재**: "1) `/commit`으로 먼저 커밋 2) 무시하고 진행 3) 취소"
- **PR 포함 커밋 0개**: 고지 후 중단.
- **이미 존재하는 PR**: 기존 URL 고지 후 중단.
- **`.github/PULL_REQUEST_TEMPLATE.md` 없음**: 고지 후 중단. (템플릿 없이 진행하지 않는다.)

### 3. 템플릿 로드 및 본문 작성

1. `.github/PULL_REQUEST_TEMPLATE.md`를 Read한다.
2. 각 섹션의 HTML 주석 가이드를 기준으로 내용을 채운다:
   - **## 개요**: 커밋 메시지들을 종합한 한 단락 요약 (변경의 "왜" 중심).
   - **## 주요 변경사항**: `git log --oneline {base}..HEAD`를 관심사별로 그룹화한 bullet.
   - **## 테스트 방법**: 템플릿의 기본 항목(`./gradlew build`) 유지, 변경 유형에 따라 체크박스 추가.
   - **## 관련 이슈**: 브랜치명·커밋에서 `#N` 추정되면 포함, 없으면 "없음".
3. HTML 주석(`<!-- ... -->`)은 최종 본문에서 제거한다.
4. **본문의 모든 문장은 존댓말로 작성한다.** 평어체(`~한다`/`~했다`)나 반말 금지. 예: `~합니다`, `~했습니다`, `~입니다`, `~유지합니다`. (제목은 기존 커밋 컨벤션 `{type} : {subject}`을 그대로 따르며 이 규칙의 예외다.)

### 4. 제목 결정

프로젝트 커밋 컨벤션 `{type} : {subject}`을 따른다.

- 커밋 1개: 해당 메시지 사용.
- 커밋 여러 개: 가장 대표적인 커밋 메시지를 선택하고 사용자에게 확인받는다.

### 5. 초안 제시 및 확인

```
📝 PR 초안:

베이스:  main
브랜치:  {current-branch}
커밋 수: {N}

제목: {title}

본문:
---
{body}
---

1) 이대로 생성
2) 제목 수정
3) 본문 수정
4) 취소
```

### 6. 원격 push

- 업스트림 미설정: `git push -u origin {current-branch}`
- 업스트림 설정됨: `git push`
- push 실패(divergence): 자동 해결하지 않고 중단.

### 7. PR 생성

```bash
gh pr create --base {base} --head {current-branch} --title "{title}" --body "$(cat <<'EOF'
{body}
EOF
)"
```

성공 시 생성된 PR URL을 출력한다.

### 8. 피드백 처리

- **2번**: 새 제목 받아 5단계로.
- **3번**: 수정할 섹션 묻고 3단계 재실행 후 5단계로.
- **4번**: 취소 종료.

## 엣지 케이스

- **`gh` 미설치·미인증**: `gh auth status` 실패 시 안내 후 중단.
- **여러 원격**: `origin`을 기본 사용.
- **fork 워크플로**: `gh pr create`가 upstream 자동 감지.

## 금지 사항

- 사용자 확인 없이 `--draft` 강제 금지.
- 영문 제목 자동 생성 금지. 커밋 컨벤션(`{type} : {subject}`) 준수.
- `git push --force`, `--force-with-lease`는 별도 요청 있을 때만.
- Co-authored-by, Claude, Anthropic 관련 내용 PR 본문 포함 금지.
- PR 본문을 평어체(`~한다`)·반말로 작성 금지. 모든 문장은 존댓말로 작성한다.
- `.github/PULL_REQUEST_TEMPLATE.md` 없으면 기본값 없이 중단. 임의 템플릿 생성 금지.
