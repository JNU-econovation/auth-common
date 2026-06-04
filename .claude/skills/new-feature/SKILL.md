---
name: new-feature
description: |
  신규 기능을 개발할 때 코드 + 테스트 + 정책 문서 + ADR을 세트로 작성한다.

  다음 상황에서 반드시 이 스킬을 사용한다:
  - "기능 추가해줘", "기획부터 해봐", "설계해봐"
  - 새로운 API 엔드포인트 또는 도메인 규칙이 생길 때
  - "/new-feature" 직접 호출

  ARGUMENTS: 기능 이름 또는 요구사항 (없으면 대화에서 추출)
---

# new-feature

신규 기능을 개발할 때의 표준 절차. **코드 → 테스트 → 정책 문서 → ADR** 순서로 작성한다.

---

## 체크리스트

```
[ ] 1. 기획 — 기능 범위, 권한, 제약 조건 정의
[ ] 2. 코드 — 구현
[ ] 3. 테스트 — 단위/통합 테스트 (성공 + 실패 케이스 포함)
[ ] 4. 정책 문서 — docs/ 에 정책/규칙 문서화
[ ] 5. ADR — 설계 결정 기록
[ ] 6. CI 확인 — push 후 CI 통과 확인
```

---

## Step 1. 기획 (코드 작성 전 필수)

사용자에게 다음을 확인하거나 대화에서 추출한다:

- **무엇을 만드나?** (기능 범위)
- **누가 쓰나?** (역할/권한 — `docs/ROLE_POLICY.md` 참조)
- **제약 조건은?** (불변 규칙, 금지 사항)
- **API 경로와 응답 형태는?**

정책이 있는 기능이라면 **기획 단계에서 바로 `docs/ROLE_POLICY.md`를 업데이트**한다.

---

## Step 2. 코드 구현

- 헥사고날 아키텍처 레이어 순서: domain → port → usecase/service → adapter
- DB 변경이 있으면 Flyway 마이그레이션 파일 작성 (`V{n+1}__{description}.sql`)
- 기존 패턴을 따른다 (`docs/CONVENTION.md` 참조)

---

## Step 3. 테스트

단위 테스트와 통합 테스트를 모두 작성한다.

### 커버해야 할 케이스

**성공 케이스:**
- 정상 입력 + 올바른 권한

**실패 케이스 (에러 코드별 1개씩):**
- 인증 없음 / 권한 부족
- 잘못된 입력 (400)
- 존재하지 않는 대상 (404)
- 비즈니스 규칙 위반 (409)
- 정책 제약 위반 (403)

```java
// 테스트 이름 컨벤션
@DisplayName("성공 케이스: [상황] → [결과]")
@DisplayName("실패 케이스: [상황] → [에러코드]")
```

---

## Step 4. 정책 문서

**정책이 있는 기능이라면 반드시 `docs/ROLE_POLICY.md` 또는 별도 정책 문서를 작성한다.**

포함 내용:
- 권한 매트릭스 (누가 무엇을 할 수 있나)
- 제약 조건 목록 + 에러 코드
- 예외 케이스 처리 방법

---

## Step 5. ADR

설계 결정이 있었다면 ADR을 작성한다.

```bash
ls docs/adr/ | sort | tail -5   # 다음 번호 확인
```

ADR을 써야 하는 경우:
- "왜 A가 아니라 B를 선택했는가"가 있을 때
- 대안을 검토하고 기각한 이유가 있을 때
- 미래에 이 결정을 번복하고 싶을 때 근거가 필요한 경우

→ `/adr` 스킬 사용

---

## Step 6. CI 확인

```bash
git push origin {branch}
gh run watch $(gh run list --repo {owner}/{repo} --branch {branch} --limit 1 --json databaseId --jq '.[0].databaseId') --repo {owner}/{repo} 2>&1 | tail -5
```

CI 실패 시 → 원인 파악 후 즉시 수정. 실패 상태로 남기지 않는다.

---

## 빠른 참조

| 항목 | 위치 |
|------|------|
| 역할/권한 정책 | `docs/ROLE_POLICY.md` |
| 코드 컨벤션 | `docs/CONVENTION.md` |
| 시퀀스 다이어그램 | `docs/SEQUENCE-DIAGRAMS.md` |
| ADR 목록 | `docs/adr/` |
| Flyway 마이그레이션 | `services/libs/member/src/main/resources/db/migration/` |
