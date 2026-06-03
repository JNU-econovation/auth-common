# disable-internal-api-key - report

## 메타
- **작업명**: disable-internal-api-key
- **작성일**: 2026-06-03
- **브랜치**: feat/member-auth
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md (DB 변경 없음 명시)
- **근거 ADR**: `docs/adr/0010-client-secret-self-service-auth.md` (ADR-0010)

---

## 진행 결과

### 1. test
- **신규 테스트**: 20개 (Controller 16 + Integration 4)
- **삭제 테스트**: 8개 (의미 상실 — `registerWithoutApiKey_returns401` 등)
- **Red 확인**: 22 FAILED + 1 skipped + 2 정당 PASSED (Bean Validation이 컨트롤러 진입 전 동작)

### 2. implementation
- **변경 파일**: main 2 / config 1 / test 2 / docs+skill 5 (implementer가 docs 일부 처리)
- **빌드/테스트 결과**: 53 tests / 1 failed (Docker 무관) / 13 skipped — Green
- **핵심 결정**:
  - Basic Auth 검증을 `AdminClientController` 내 private 메서드 (`verifyBasicAuth`, `parseBasicAuth`)
  - BCrypt 비교: `{bcrypt}` prefix를 `substring(8)`로 수동 제거 후 `BCryptPasswordEncoder(12).matches()`
  - 401 응답에 `WWW-Authenticate: Basic realm="admin"` 헤더 추가 (RFC 7235)
  - 에러 응답은 기존 `AdminClientController.ErrorResponse` record 활용

### 3. code-review
- **리뷰 항목**: 7개 (반영 권장 4 / 참고 3)
- **반영 권장**:
  - #1 [critical] `storedSecret == null` 처리 누락 → `authorization_code` 클라이언트가 Basic Auth 시도 시 `BCryptPasswordEncoder.matches(raw, null)` → IllegalArgumentException → 500 + 예외 스택 노출. **명시적 null 분기 추가**
  - #2 [major] `verifyBasicAuth` null-means-success 패턴 → `parseBasicAuth` 이중 호출 + Base64 디코딩 2회 + 미래 일관성 위험. **`AuthResult` record 도입**으로 인증 결과를 명확히 전달
  - #3 [major] `AuthApiIntegrationTest.add_redirect_uri` 통합 테스트 plan 미이행 (현재 401 케이스만). **client_credentials full 시나리오로 재작성**
  - #4 [minor] plan P-2/P-4 — "구 `X-Internal-Api-Key` 헤더 포함 요청도 무시하고 정상 동작" 하위 호환 테스트 누락. **2개 추가**
- **재검증 결과**: 53 tests / 1 failed (Docker 무관) / 13 skipped — Green 유지

### 4. docs
- **갱신 파일**: 5 (implementer 처리) + 2 (docs-writer 추가 보완)
- **추가 보완**:
  - `docs/CLIENT_REGISTRATION.md`: `WWW-Authenticate: Basic realm="admin"` 헤더 명시 + `authorization_code` 클라이언트 Basic Auth 불가 경고
  - `docs/ARCHITECTURE.md`: 에러 코드 체계에 `auth-api — Admin API` 섹션 신규 (`INVALID_CLIENT_CREDENTIALS`/401, `FORBIDDEN_CLIENT_MISMATCH`/403)

### 5. doc-review
- **리뷰 항목**: 9개 (반영 권장 6 / 참고 3)
- **반영 5개** (5번째까지):
  - #1 [critical] `docs/outcomes/` 2개 파일 갱신 누락 — `X-Internal-Api-Key` 잔존 → 일괄 정리 + API 표 인증 컬럼 갱신
  - #2 [major] `docs/ARCHITECTURE.md` Admin API 섹션 하단의 잘못된 `> 정의:` 포인터 제거 (auth-core 회원 도메인 경로가 Admin API 에러 정의처럼 읽혔음)
  - #3 [major] `docs/FEATURES.md` Step 1 curl에 `Content-Type: application/json` 헤더 추가
  - #4 [major] `docs/FEATURES.md` Step 1 주석에 `clientSecret 1회만 노출 — 반드시 즉시 저장` 안내 추가
  - #5 [minor] `docs/CLIENT_REGISTRATION.md` `clientSecret` 미발급 이유 정확화 (`@JsonInclude(NON_NULL)`로 제외되는 게 아니라 **생성 자체가 안 됨**)
- **반영 후 갱신 파일**: 5개 (outcomes 2 + ARCHITECTURE + FEATURES + CLIENT_REGISTRATION)
- **#6 (구어체→문어체 통일)은 제외**: 기존부터 존재하던 불일치라 별도 작업 후보

---

## 변경 요약

### 신규 파일 (1)
- `.claude/plans/disable-internal-api-key/report.md` (본 보고서)

### 수정 파일 (main 2)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`

### 수정 파일 (test 2)
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java`

### 수정 파일 (config 1)
- `services/apis/auth-api/src/test/resources/application-test.yml`

### 갱신 docs/skill (8)
- `docs/CLIENT_REGISTRATION.md`
- `docs/FEATURES.md`
- `docs/DYNAMIC_ROUTING.md`
- `docs/ARCHITECTURE.md`
- `docs/outcomes/01-auth-capabilities.md`
- `docs/outcomes/02-integrating-new-service.md`
- `services/apis/auth-api/README.md`
- `.claude/skills/register-service/SKILL.md`

---

## plan과의 차이

### plan 그대로 이행
- 모든 7개 admin endpoint에서 `X-Internal-Api-Key` 시스템 제거
- 4개 endpoint에 Basic Auth(`Authorization: Basic`) 검증 + path/Basic clientId 일치 강제
- 2개 endpoint(`POST /clients`, `GET /routes`) public
- `GatewayRoutingConfig.java` 경고 주석
- `application-test.yml`에서 환경변수 제거
- `register-service` 스킬 갱신

### implementer가 추가로 손댄 부분 (docs 4단계 일부 선행)
- `docs/CLIENT_REGISTRATION.md`, `docs/FEATURES.md`, `docs/DYNAMIC_ROUTING.md`, `services/apis/auth-api/README.md`, `.claude/skills/register-service/SKILL.md`
- → 4단계(docs)에서 docs-writer가 추가 보완 위주 작업 + doc-review에서 누락된 outcomes/ 발견·정리

### code-review에서 발견된 critical 1건
- **`authorization_code` 클라이언트의 `storedSecret == null` 처리 누락** → `BCryptPasswordEncoder.matches(raw, null)` IllegalArgumentException → 500 응답 위험 → 명시적 null 분기로 해결. 이 패턴은 OAuth client 메타데이터 모델의 nullable 특성에서 비롯되므로 향후 Basic Auth 채널 확장 시 참고 사항.

### doc-review에서 발견된 critical 1건
- **`docs/outcomes/` 2개 파일 갱신 누락** → docs-writer 호출 시 검토 범위에서 `outcomes/` 디렉터리를 명시하지 않은 게 원인. 향후 docs-writer 호출 시 outcomes/도 검토 대상에 포함하도록 컨벤션 갱신 후보.

---

## ADR-0010 이행 상태

| 결정 항목 | 본 작업 이행 |
|---|---|
| Internal API Key 시스템 제거 | ✅ |
| `POST /admin/clients` public 전환 | ✅ |
| `GET /admin/routes` public 전환 | ✅ |
| redirect-uris CRUD + `GET /clients/{id}` Basic Auth | ✅ |
| path/Basic clientId 일치 강제 (403) | ✅ |
| `AUTH_INTERNAL_API_KEY` 환경변수 + 검증 로직 제거 | ✅ |
| Gateway admin 외부 라우트 추가 금지 (전제 유지) | ✅ (경고 주석) |
| HTTPS 전제 (Basic Auth 평문 base64) | ⚠️ 운영 사항 (코드 변경 없음) |
| BCrypt 비교 비용 (~10ms) | ✅ 인지 |
| secret 회전 endpoint 부재 | ⏳ 별도 작업 후보 |

ADR-0010 본문의 폐기 결정 중 `grantType`, `redirectUris`, `apiKeyHash` 등은 **직전 작업 `refactor-client-registration`(`2ab06d0`)에서 nullable 보존 결정으로 supersede**됐으므로 본 작업에 포함되지 않음.

---

## 다음 단계

- `/commit` 으로 커밋 (단일 또는 그룹별 분리)
- `/git-pr` 로 PR 생성 (또는 그냥 push)
