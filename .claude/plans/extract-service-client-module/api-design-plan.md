# extract-service-client-module - api-design

## 메타
- **작업명**: extract-service-client-module
- **문서 타입**: api-design
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md

## 개요

이번 작업은 **API 변경 없음**. `AdminClientController`가 제공하는 6개 엔드포인트의 URL, HTTP 메서드, 요청/응답 스키마, 인증 방식, 에러 코드는 모두 그대로 유지된다. 변경 범위는 서비스·도메인·예외 클래스의 패키지 이동(`com.econo.auth.api.*` → `com.econo.auth.client.*`)에 한정되며, 호출자가 인지할 수 있는 외부 계약 차이는 없다.

프로토콜: REST / Spring MVC (`@RestController`, `@RequestMapping("/api/v1")`)

---

## 본문

### 영향 받는 엔드포인트 — 변경 없음 확인용

| 메서드 | 경로 | 설명 | 인증 / 권한 |
|--------|------|------|-------------|
| POST | `/api/v1/clients` | OAuth 클라이언트 등록 | public (인증 불필요) |
| GET | `/api/v1/routes` | Gateway 라우트 목록 조회 | public (인증 불필요) |
| GET | `/api/v1/clients/{clientId}` | 클라이언트 조회 (redirectUri 포함) | Basic Auth 필수 |
| POST | `/api/v1/clients/{clientId}/redirect-uris` | redirectUri 추가 | Basic Auth 필수 |
| DELETE | `/api/v1/clients/{clientId}/redirect-uris` | redirectUri 제거 | Basic Auth 필수 |
| PUT | `/api/v1/clients/{clientId}/redirect-uris` | redirectUri 전체 교체 | Basic Auth 필수 |

Basic Auth 형식: `Authorization: Basic base64(clientId:clientSecret)`
path `{clientId}`와 Basic Auth `clientId`가 불일치하면 403 `FORBIDDEN_CLIENT_MISMATCH` 반환 (기존과 동일).

---

### 예외 패키지 이동 매핑

| 예외 클래스 | errorCode | HTTP 상태 | 이동 전 패키지 | 이동 후 패키지 |
|-------------|-----------|-----------|----------------|----------------|
| `RedirectUriRequiredException` | `REDIRECT_URI_REQUIRED` | 400 | `com.econo.auth.api.exception` | `com.econo.auth.client.exception` |
| `UnsupportedGrantTypeException` | `UNSUPPORTED_GRANT_TYPE` | 400 | `com.econo.auth.api.exception` | `com.econo.auth.client.exception` |
| `DuplicateClientNameException` | `DUPLICATE_CLIENT_NAME` | 409 | `com.econo.auth.api.exception` | `com.econo.auth.client.exception` |
| `InvalidClientException` | `CLIENT_NOT_FOUND` | 404 | `com.econo.auth.api.exception` | `com.econo.auth.client.exception` |

에러 코드·HTTP 상태·응답 바디 구조(`ApiError`)는 변경하지 않는다. `GlobalExceptionHandler`의 `@ExceptionHandler` 메서드(특히 `handleInvalidClient`)는 import 경로만 갱신한다.

---

### 하위 호환성 보장

- URL, HTTP 메서드, 요청/응답 JSON 스키마 변경 없음.
- 에러 응답 `{"errorCode": "...", "message": "...", "timestamp": "..."}` 구조 변경 없음.
- `Authorization: Basic` 헤더 방식 및 `WWW-Authenticate: Basic realm="admin"` 응답 변경 없음.
- 호출자(API 클라이언트, Gateway)가 인지할 차이 없음 — 재배포만으로 투명하게 전환됨.

---

### 통합 테스트 시나리오

패키지 이동 후 다음 테스트가 import 경로 갱신만으로 그린 상태를 유지해야 한다.

| 테스트 파일 | 위치 | 기대 결과 |
|-------------|------|-----------|
| `AdminClientControllerTest.java` | `auth-api/src/test/...` (잔류) | import 경로 갱신 후 전원 통과 |
| `AuthApiIntegrationTest.java` | `auth-api/src/test/...` (잔류) | import 경로 갱신 후 전원 통과 |

---

## 체크리스트
- [x] todo의 모든 API 작업이 명세됨 (todo API 작업 섹션: "해당 없음" 확인)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (public 2개, Basic Auth 4개)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 (예외 4개 이동 후 errorCode/HTTP 상태 불변)
- [x] 하위 호환성 보장 선언됨
- [x] 통합 테스트 시나리오 명시됨

## 참고
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`
