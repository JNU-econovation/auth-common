# login-redirect-via-body - api-design

## 메타
- **작업명**: login-redirect-via-body
- **문서 타입**: api-design
- **작성일**: 2026-06-18
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

이 문서는 `POST /api/v1/auth/login` 단일 엔드포인트의 WEB 분기 응답 방식 변경을 명세한다. WEB 분기에서 서버가 수행하던 `302 Found + Location` 리다이렉트를 제거하고, `200 OK + body { redirectUrl }` 으로 교체한다. SSO 토큰(AT/RT)은 기존과 동일하게 HttpOnly 쿠키로 발급된다. APP 분기와 인증 실패 응답은 변경 없다. 프로토콜은 REST(Spring MVC + JSON), 인증은 자격증명 기반(Spring Security `JsonLoginAuthenticationFilter` 처리)이다.

> **[2026-06-18 accessExpiredTime WEB 제거 반영]** 최종 확정 계약에 따라 WEB 로그인 body는 `{ redirectUrl }`만 포함한다. `accessExpiredTime`은 WEB body에서 제거됐다. WEB 재발급 body는 `{}`다. 후속 작업자는 WEB 분기에 `accessExpiredTime`을 재삽입하지 않는다.

## 본문

### 엔드포인트 목록

| 메서드 / 작업 | 경로 / 식별자 | 설명 | 인증 / 권한 | 연관 todo |
|---|---|---|---|---|
| POST | /api/v1/auth/login (WEB 분기) | JSON 자격증명 로그인 — 쿠키 SSO + redirectUrl body 반환 | 인증 불필요 (자격증명 자체가 인증 수단) | API 작업 #1 |
| POST | /api/v1/auth/login (Swagger 갱신) | LoginOpenApiCustomizer WEB 302 → 200 명세 교체 | - | API 작업 #2 |

---

### 엔드포인트 상세

#### POST /api/v1/auth/login

- **목적**: JSON 자격증명(loginId, password)으로 인증. WEB 분기는 AT/RT를 HttpOnly 쿠키로 발급하고 `redirectUrl`을 body로 반환한다. APP 분기는 기존 동작(AT/RT body + redirectUrl) 유지.
- **연관 todo**:
  - `[ ] POST /api/v1/auth/login WEB 분기 응답 변경: 302 Found → 200 OK + body { "redirectUrl": "<url>" }` ([2026-06-18 accessExpiredTime WEB 제거 반영]: accessExpiredTime 제거)
  - `[ ] Swagger 명세(LoginOpenApiCustomizer) WEB 분기 응답 수정: 302 응답 항목을 200 응답(body: { redirectUrl })으로 교체하고, 기존 302 항목 제거` ([2026-06-18 accessExpiredTime WEB 제거 반영])
- **요청 헤더 / 메타데이터**:
  - `Content-Type: application/json` (필수)
  - `Client-Type: WEB | APP` (선택. 생략 시 WEB으로 간주. `APP` 이외 모든 값은 WEB 분기)
  - `Cookie` 헤더: 불필요 (이 엔드포인트 자체는 인증 없이 접근 가능)
- **요청 바디 / 파라미터**:
  ```json
  {
    "loginId": "hong",
    "password": "password123!",
    "clientId": "econovation-web"
  }
  ```
  - `loginId` (String, 필수): 로그인 아이디
  - `password` (String, 필수): 비밀번호
  - `clientId` (String, 선택): OAuth 클라이언트 ID. redirect_uri 결정에 사용. 없거나 미등록이면 `auth.redirect.default-url`로 fallback.

---

#### [WEB 분기] Client-Type 헤더 없음 또는 APP 이외 값

- **응답 (성공)**:
  - 상태: `200 OK`
  - 응답 헤더:
    ```
    Content-Type: application/json;charset=UTF-8
    Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Path=/; Max-Age=3600
    Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Path=/; Max-Age=2592000
    ```
  - 바디:
    ```json
    {
      "redirectUrl": "https://app.econovation.kr/callback"
    }
    ```
  - `accessToken` 필드: `null` — `@JsonInclude(NON_NULL)` 적용으로 직렬화 결과에서 **제외됨**
  - `refreshToken` 필드: `null` — 동일하게 직렬화 결과에서 **제외됨**
  - `accessExpiredTime` 필드: `null` — **[2026-06-18 accessExpiredTime WEB 제거 반영]** WEB body에서 제거됨. 직렬화 결과에서 **제외됨**.
  - `redirectUrl` (String): `LoginRedirectUseCase.resolve(clientId, defaultUrl)` 결과. clientId 미전달·미등록·redirect_uri 없음 모두 `auth.redirect.default-url`로 fallback (4xx 거부 없음).
  - 비고: `accessToken`·`refreshToken`·`accessExpiredTime`은 WEB body에 포함하지 않는다. `LoginResponse.web(String redirectUrl)` 팩토리로 생성.

- **응답 (에러)**:
  - `401` `INVALID_CREDENTIALS` — loginId/password 불일치. 기존 동작 유지.
    ```json
    {
      "errorCode": "INVALID_CREDENTIALS",
      "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
      "timestamp": "2026-06-18T10:00:00",
      "fieldErrors": null
    }
    ```
  - 비고: 이 응답은 `JsonLoginAuthenticationFilter.unsuccessfulAuthentication()`이 직접 직렬화한다. `GlobalExceptionHandler`를 거치지 않는다. 포맷 변경 없음.

- **인증 / 권한**:
  - 필요 여부: 없음. 이 엔드포인트 자체가 인증 수단이다.
  - 필요 역할/스코프: 없음.
  - 추가 조건: Spring Security `SecurityConfig`에서 `/api/v1/auth/login`은 `permitAll()`로 열려 있어야 한다 (기존 설정 유지).

---

#### [APP 분기] Client-Type: APP

- **목적**: 변경 없음. 기존 동작 유지 명세.
- **응답 (성공)**:
  - 상태: `200 OK`
  - 응답 헤더:
    ```
    Content-Type: application/json;charset=UTF-8
    ```
    (Set-Cookie 없음 — APP은 쿠키 미사용)
  - 바디:
    ```json
    {
      "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
      "accessExpiredTime": 1780242839681,
      "refreshToken": "eyJhbGciOiJSUzI1NiJ9...",
      "redirectUrl": "https://app.econovation.kr/callback"
    }
    ```
  - `LoginResponse.app(String, long, String, String)` 팩토리로 생성. 변경 없음.

- **응답 (에러)**: WEB과 동일한 `INVALID_CREDENTIALS` 401. 변경 없음.

- **인증 / 권한**: WEB 분기와 동일. 변경 없음.

---

#### Swagger 명세 — LoginOpenApiCustomizer 갱신 포인트

연관 todo: `[ ] Swagger 명세(LoginOpenApiCustomizer) WEB 분기 응답 수정: 302 응답 항목을 200 응답(body: { redirectUrl })으로 교체, 기존 302 항목 제거` ([2026-06-18 accessExpiredTime WEB 제거 반영])

**현재 loginResponses() 구성 (변경 전)**:

| 응답 코드 | 설명 | body 스키마 |
|---|---|---|
| 200 | APP 로그인 성공 — body로 토큰 반환 | `{ accessToken, accessExpiredTime, refreshToken, redirectUrl }` |
| 302 | WEB 로그인 성공 — at/rt HttpOnly 쿠키 설정 후 redirect_uri로 리다이렉트 | (없음) |
| 401 | INVALID_CREDENTIALS — 자격증명 불일치 | (없음) |

**변경 후 loginResponses() 구성**:

| 응답 코드 | 설명 | body 스키마 |
|---|---|---|
| 200 (WEB) | WEB 로그인 성공 — at/rt HttpOnly 쿠키 설정, body에 redirectUrl 반환 | `{ redirectUrl }` |
| 200 (APP) | APP 로그인 성공 — body로 토큰 반환 | `{ accessToken, accessExpiredTime, refreshToken, redirectUrl }` |
| 401 | INVALID_CREDENTIALS — 자격증명 불일치 | (없음) |

- `302` 응답 항목 제거.
- WEB 200 응답 description에 Set-Cookie(`at`, `rt`) 동작을 명시한다.
  - 예시 description: `"WEB 로그인 성공 — at/rt HttpOnly 쿠키(SameSite=None; Secure; Domain=.econovation.kr) 설정, body에 redirectUrl만 반환. accessToken·refreshToken·accessExpiredTime은 body에 포함되지 않음(쿠키 전용 또는 WEB 불필요 필드)."`
- 두 개의 200 응답은 Swagger OpenAPI 3.0에서 단일 `"200"` 키에 merged되므로, description에 WEB/APP 분기를 함께 설명하거나 `addApiResponse("200", ...)` 호출을 하나로 통합한다.
  - 권장: 단일 `"200"` 응답 항목, description에서 WEB/APP 분기를 각각 서술, body 스키마는 `LoginResponse` record 전체 필드(`accessToken nullable, accessExpiredTime, refreshToken nullable, redirectUrl`)로 표현.
- WEB 분기 body 스키마 예시 ([2026-06-18 accessExpiredTime WEB 제거 반영]):
  ```json
  {
    "redirectUrl": "https://app.econovation.kr/callback"
  }
  ```
- APP 분기 body 스키마 예시:
  ```json
  {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "accessExpiredTime": 1780242839681,
    "refreshToken": "eyJhbGciOiJSUzI1NiJ9...",
    "redirectUrl": "https://app.econovation.kr/callback"
  }
  ```

---

### 공통 헤더 / 프로젝트 표준

| 구분 | 헤더 | 설명 |
|---|---|---|
| 요청 | `Client-Type` | WEB / APP 분기 결정. 생략 시 WEB. |
| 응답 (WEB) | `Set-Cookie: at=...` | AT JWT, HttpOnly, SameSite=None, Secure, Domain=.econovation.kr, Max-Age=3600 |
| 응답 (WEB) | `Set-Cookie: rt=...` | RT JWT, HttpOnly, SameSite=None, Secure, Domain=.econovation.kr, Max-Age=2592000 |
| 응답 (공통) | `Content-Type: application/json;charset=UTF-8` | JSON body 응답 시 |

**쿠키 세팅 순서 제약**: `cookieManager.setAtCookie()` → `cookieManager.setRtCookie()` → `response.setStatus(SC_OK)` → `objectMapper.writeValue()` 순서를 유지한다. 응답 커밋(`response.sendRedirect()` 제거 이후에도) 이전에 쿠키 헤더가 먼저 추가되어야 한다 (ADR-0012 제약사항 인용, `sendRedirect` 제거 후에도 동일 순서 유지).

**CORS**: `allowCredentials=true`, FE 오리진이 `CORS_ALLOWED_ORIGINS` 환경변수로 허용 목록에 포함되어야 한다. FE는 `credentials: 'include'`로 fetch 호출. CORS 설정 변경은 이 작업 범위에 포함되지 않으나 API 명세 전제 조건이다.

---

### 에러 코드 체계 (변경 없음)

이 엔드포인트에서 발생 가능한 에러는 모두 기존 체계를 그대로 사용한다. 신규 에러 코드 없음.

| HTTP | errorCode | 발생 조건 | 처리 위치 |
|---|---|---|---|
| 401 | `INVALID_CREDENTIALS` | loginId/password 불일치 | `JsonLoginAuthenticationFilter.unsuccessfulAuthentication()` 직접 직렬화 |

- `clientId` 미전달·미등록·redirect_uri 빈 Set → 에러가 아닌 fallback (`auth.redirect.default-url`). 기존 정책 유지 (ADR-0012).
- 인프라·DB 오류 시에도 `auth.redirect.default-url`로 fallback. 에러 응답 없음.

---

### LoginResponse record 필드 직렬화 요약

`@JsonInclude(JsonInclude.Include.NON_NULL)` 적용 전제.

| 분기 | accessToken | accessExpiredTime | refreshToken | redirectUrl |
|---|---|---|---|---|
| WEB (변경 후) | `null` → 직렬화 제외 | `null` → 직렬화 제외 (WEB 제거) | `null` → 직렬화 제외 | 포함 |
| APP | 포함 | 포함 | 포함 | 포함 |

---

## 체크리스트

- [x] todo의 모든 API 작업이 엔드포인트로 명세됨
  - API 작업 #1 (WEB 분기 302 → 200 body): WEB 분기 상세 명세
  - API 작업 #2 (Swagger LoginOpenApiCustomizer 302 → 200 교체): Swagger 갱신 포인트 명세
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X)
  - 인증 불필요(`permitAll`), 자격증명 자체가 인증 수단임을 명시
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨
  - `INVALID_CREDENTIALS` 401 — 기존 체계 유지, 신규 코드 없음
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨
  - WEB/APP 분기별 JSON 예시 포함, Set-Cookie 헤더 예시 포함
- [x] 프로젝트 표준 헤더/메타데이터가 누락 없이 명시됨
  - `Client-Type`, `Set-Cookie`, `Content-Type`, CORS 전제 조건, 쿠키 세팅 순서 제약 명시

## 참고

- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/JsonLoginAuthenticationFilter.java` — 기존 WEB 분기 103-112행 (`sendRedirect` 제거 대상)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/LoginResponse.java` — `web()` 팩토리 `@Deprecated` 상태, `app()` 4인수 팩토리 참조
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/openapi/LoginOpenApiCustomizer.java` — 302 응답 명세 59-71행 (교체 대상)
- `docs/adr/0012-backend-decided-login-redirect.md` — 리다이렉트 결정 주체(백엔드 clientId)·fallback 정책·쿠키 세팅 순서 제약 근거
- `docs/ARCHITECTURE.md` — [흐름 A] 105-122행, 에러 코드 체계
- `docs/CONVENTION.md` — 8.6 필터 처리 엔드포인트(OpenApiCustomizer), 8.7 DTO 스키마 문서화
- `services/apis/auth-api/README.md` — POST /api/v1/auth/login 73-84행 (WEB 302 응답 예시, 갱신 대상)
