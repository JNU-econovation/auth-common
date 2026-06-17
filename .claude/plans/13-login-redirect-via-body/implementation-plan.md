# login-redirect-via-body - implementation

## 메타
- **작업명**: login-redirect-via-body
- **문서 타입**: implementation
- **작성일**: 2026-06-18
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

WEB 로그인 성공 핸들러(`JsonLoginAuthenticationFilter.successfulAuthentication()`)에서 `response.sendRedirect()` 호출을 제거하고, APP 분기와 동일한 `objectMapper.writeValue` 직렬화 방식으로 `200 OK + body({ redirectUrl })`를 반환하도록 전환한다. SSO 쿠키(AT/RT HttpOnly) 발급은 그대로 유지된다. 영향 파일은 `auth-api` 모듈 내 3개 소스 파일과 2개 테스트 파일이며 DB 변경·모듈 추가·의존 관계 변경은 없다. Java 21 + Spring Boot 3.2.2 + Spring MVC 스택, Gradle 멀티모듈 `services/apis/auth-api` 모듈 위에서 설계된다.

> **[2026-06-18 accessExpiredTime WEB 제거 반영]** 최종 확정 계약에 따라 WEB 로그인 body는 `{ redirectUrl }`만 포함한다. `accessExpiredTime`은 WEB body에서 제거됐다. 후속 작업자는 WEB 분기에 `accessExpiredTime`을 재삽입하지 않는다. WEB 재발급 body는 `{}`다.

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/apis/auth-api` — `config/security/` | 변경 | `JsonLoginAuthenticationFilter` WEB 분기 로직 수정 (sendRedirect 제거, body 직렬화 추가) |
| `services/apis/auth-api` — `presentation/dto/` | 변경 | `LoginResponse.web()` 팩토리 시그니처 변경, `@Deprecated` 제거 |
| `services/apis/auth-api` — `config/openapi/` | 변경 | `LoginOpenApiCustomizer` WEB 302 응답 → 200+body 응답으로 교체 |
| `services/apis/auth-api` — `presentation/dto/` (test) | 변경 | `LoginResponseTest` WEB 분기 단위 테스트 추가 |
| `services/apis/auth-api` — `integration/` (test) | 변경 | `AuthApiIntegrationTest` WEB 분기 기대값 갱신, APP 비교 케이스 수정 |

### 구성 요소 설계

#### 모듈 / 패키지: `services/apis/auth-api/src/main/java/com/econo/auth/api/`

```
config/security/
└── JsonLoginAuthenticationFilter     — WEB 분기 successfulAuthentication() 수정

presentation/dto/
└── LoginResponse                     — web() 팩토리 시그니처 변경 + @Deprecated 제거

config/openapi/
└── LoginOpenApiCustomizer            — WEB 302 응답 명세 → 200+body 명세로 교체
```

---

##### JsonLoginAuthenticationFilter
- **타입**: Security Filter (`config/security/` — Spring Security에 결합된 클래스 전용)
- **책임**: JSON 로그인 요청을 처리하고 인증 성공 시 AT/RT를 발급한다. WEB 분기에서 302 리다이렉트 대신 200 OK + body(`{ redirectUrl }`)를 반환하도록 변경한다.
- **변경 메서드**: `successfulAuthentication(HttpServletRequest, HttpServletResponse, FilterChain, Authentication)`
  - **제거**: `response.sendRedirect(target)` (라인 111)
  - **추가**: `response.setStatus(HttpServletResponse.SC_OK)`, `response.setContentType(MediaType.APPLICATION_JSON_VALUE)`, `response.setCharacterEncoding("UTF-8")`, `objectMapper.writeValue(response.getWriter(), LoginResponse.web(target))` ([2026-06-18 accessExpiredTime WEB 제거 반영]: `tokens.accessExpiredAt()` 전달 불필요)
  - **유지**: `cookieManager.setAtCookie(response, tokens.accessToken())` / `cookieManager.setRtCookie(response, tokens.refreshToken())` — 쿠키 세팅은 body 직렬화보다 먼저 수행해야 한다 (응답 커밋 전 헤더 설정 원칙 동일)
  - **순서**: ① AT 쿠키 세팅 → ② RT 쿠키 세팅 → ③ redirectUrl 결정(`loginRedirectUseCase.resolve`) → ④ status/contentType 세팅 → ⑤ body 직렬화 (APP 분기와 동일한 순서)
- **변경 Javadoc**: 클래스 상단 Javadoc WEB 분기 설명 "302 리다이렉트" → "200 OK + body(`{ redirectUrl }`) + HttpOnly 쿠키"
- **의존성**: `LoginRedirectUseCase`, `LoginTokenUseCase`, `TokenCookieManager`, `ObjectMapper`, `LoginResponse`
- **적용 컨벤션**:
  - `config/security/` 패키지에만 위치 — `presentation/filter` 패키지 사용 금지 (`docs/CONVENTION.md` 1.1절)
  - APP 분기 직렬화 패턴(`setStatus` → `setContentType` → `setCharacterEncoding` → `writeValue`) 그대로 미러링
  - 클래스 Javadoc 수정 시 `@param`·`@return` 태그 필수, `<p>` 태그로 분기 설명 분리 (`docs/CONVENTION.md` 4.1절)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/JsonLoginAuthenticationFilter.java:92-102` (APP 분기 직렬화 패턴 미러링)
- **연관 todo**:
  - `[ ] JsonLoginAuthenticationFilter.successfulAuthentication() WEB 분기 수정`
  - `[ ] JsonLoginAuthenticationFilter 클래스 Javadoc WEB 분기 설명 갱신`

---

##### LoginResponse
- **타입**: DTO record (`presentation/dto/`)
- **책임**: 로그인·재발급 응답을 표현하는 불변 record. `web()` 정적 팩토리를 WEB 200+body 전환에 맞게 복원·재설계한다.
- **변경 메서드**:
  - `web(long accessExpiredTime)` — 현재 시그니처. `@Deprecated` 붙어 있음.
    - **변경**: 시그니처를 `web(String redirectUrl)`로 교체. `@Deprecated` 제거. `return new LoginResponse(null, null, null, redirectUrl)` 반환. (accessToken/refreshToken/accessExpiredTime은 WEB body 불포함 — [2026-06-18 accessExpiredTime WEB 제거 반영])
    - **Javadoc 갱신**: "WEB: AT + RT HttpOnly 쿠키 발급. body에는 redirectUrl만 포함."
  - **호출처 확인**: 현재 `web(long)` 단일 인자 버전을 호출하는 곳 — `JsonLoginAuthenticationFilter.java`에서 현재 `@Deprecated` 팩토리 미사용 (WEB 분기가 `sendRedirect`만 했으므로). 따라서 기존 1인자 호출처는 없음. 새 2인자 시그니처를 `JsonLoginAuthenticationFilter` WEB 분기에서 신규 호출.
- **record 필드 변경 없음**: `accessToken`, `accessExpiredTime`, `refreshToken`, `redirectUrl` 4개 필드 유지. `@JsonInclude(JsonInclude.Include.NON_NULL)` 유지 — WEB에서 `accessToken`/`refreshToken`/`accessExpiredTime`이 null이면 JSON에서 제외됨. ([2026-06-18 accessExpiredTime WEB 제거 반영])
- **의존성**: Jackson (`@JsonInclude`, `@Schema`)
- **적용 컨벤션**:
  - record 타입 유지 — 불변 DTO (`docs/CONVENTION.md` 2.3절)
  - `@JsonInclude(NON_NULL)` 클래스 레벨 선언 유지 — null 필드 JSON 제외 (`docs/CONVENTION.md` 2.4절)
  - `@Schema(nullable = true)` — `accessToken`, `refreshToken`은 WEB에서 null이므로 nullable 유지 (`docs/CONVENTION.md` 8.7절)
  - Javadoc `@param`, `@return` 필수 (`docs/CONVENTION.md` 4.1절)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/LoginResponse.java:55-71` (`app(4인자)` 팩토리 패턴 미러링)
- **연관 todo**: `[ ] LoginResponse.web(String redirectUrl) 팩토리 시그니처 변경` ([2026-06-18 accessExpiredTime WEB 제거 반영]: 2인자 `web(long, String)` 아님, 1인자 `web(String)`)

---

##### LoginOpenApiCustomizer
- **타입**: Config (`config/openapi/` — 전역 OpenAPI 커스터마이저)
- **책임**: Spring Security 필터가 처리하는 로그인 엔드포인트의 Swagger 명세를 직접 주입한다. WEB 분기 응답을 `302` → `200 + body`로 교체한다.
- **변경 메서드**: `loginResponses()` (private)
  - **제거**: `.addApiResponse("302", ...)` 항목 전체 (라인 67-70)
  - **변경**: `"200"` 항목 description을 `"APP 또는 WEB 로그인 성공 — body로 반환"` 형태로 통합. body schema는 WEB/APP 모두 `loginResponseSchema()`를 공유한다 (WEB에서 `accessToken`/`refreshToken`이 null로 직렬화 제외됨은 `@JsonInclude(NON_NULL)` + `@Schema(nullable=true)`로 표현).
  - **또는** WEB 전용 `"200"` 응답을 별도 description으로 남기되 schema 참조는 동일 — api-design-plan.md 명세를 따른다.
  - `loginOperation()` 내 `description` 필드: "WEB ... 302" 문구를 "WEB ... 200 OK + body({ redirectUrl })" 로 갱신 ([2026-06-18 accessExpiredTime WEB 제거 반영])
- **적용 컨벤션**:
  - `config/openapi/` 패키지에 위치 (`docs/CONVENTION.md` 8.2절)
  - 필터 처리 엔드포인트 → `OpenApiCustomizer` 구현체로 명세 직접 추가 (`docs/CONVENTION.md` 8.6절)
  - `@Component` 유지 (스프링 빈 자동 등록)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/openapi/LoginOpenApiCustomizer.java:59-71` (변경 대상 `loginResponses()` 메서드)
- **연관 todo**: `[ ] Swagger: LoginOpenApiCustomizer WEB 분기 302 응답 → 200+body 응답으로 교체`

---

#### 모듈 / 패키지: `services/apis/auth-api/src/test/java/com/econo/auth/api/`

```
presentation/dto/
└── LoginResponseTest        — web() 팩토리 단위 테스트 추가

integration/
└── AuthApiIntegrationTest   — WebLoginTest WEB 분기 기대값 갱신,
                               AppLoginTest.web_login_response_does_not_contain_redirectUrl_in_body 갱신
```

---

##### LoginResponseTest (변경)
- **타입**: 단위 테스트
- **책임**: `LoginResponse` 팩토리 메서드와 JSON 직렬화를 검증한다. WEB 분기 `web(long, String)` 팩토리 케이스를 추가한다.
- **추가할 테스트 케이스** (`@Nested @DisplayName("web() 팩토리 메서드")` 클래스 신규 추가):
  - `web_withRedirectUrl_setsRedirectUrl()` — `web("https://app.example.com")` 호출 후 `redirectUrl` 값 검증, `accessToken`/`refreshToken`/`accessExpiredTime` null 검증 ([2026-06-18 accessExpiredTime WEB 제거 반영])
  - `web_withNullRedirectUrl_redirectUrlIsNull()` — `web(null)` 호출 후 `redirectUrl == null` 검증
  - `serialize_webResponse_excludesAccessTokenAndRefreshTokenAndAccessExpiredTime()` — JSON 직렬화 후 `accessToken`, `refreshToken`, `accessExpiredTime` 키 미포함 검증 (`@JsonInclude(NON_NULL)`) ([2026-06-18 accessExpiredTime WEB 제거 반영])
  - `serialize_webResponse_includesOnlyRedirectUrl()` — JSON 직렬화 후 `redirectUrl` 포함, `accessExpiredTime` 미포함 검증 ([2026-06-18 accessExpiredTime WEB 제거 반영])
- **적용 컨벤션**:
  - `@Nested` + `@DisplayName` 한글 테스트명 (`docs/CONVENTION.md` 5.1절)
  - Given-When-Then 주석 구분 (`docs/CONVENTION.md` 5.2절)
  - AssertJ `assertThat` fluent assertion (`docs/CONVENTION.md` 5.2절)
  - `ObjectMapper` 직접 인스턴스화 (Spring 컨텍스트 불필요)
- **참조할 기존 코드**: `services/apis/auth-api/src/test/java/com/econo/auth/api/presentation/dto/LoginResponseTest.java:27-55` (`AppFactoryMethodTest` 클래스 구조 미러링)
- **연관 todo**: `[ ] LoginResponseTest WEB 분기 단위 테스트 추가`

---

##### AuthApiIntegrationTest (변경)
- **타입**: 통합 테스트 (`@SpringBootTest` + MockMvc + Testcontainers PostgreSQL)
- **책임**: auth-api 전체 E2E 흐름을 검증한다. WEB 로그인 분기 기대값을 302→200+body로 갱신하고, APP 테스트 내 WEB 비교 케이스를 수정한다.

**WebLoginTest 내 수정 대상 테스트 (6건)**:

| 테스트 메서드 | 변경 내용 |
|---|---|
| `web_login_issues_cookies_and_redirects` | `status().is3xxRedirection()` → `status().isOk()`. `body.doesNotContain("accessToken"/"refreshToken"/"accessExpiredTime")` 유지/추가. `jsonPath("$.redirectUrl").isNotEmpty()` 추가. `assertThat(atCookie/rtCookie).isNotBlank()` 유지. `jsonPath("$.accessExpiredTime")` 미포함 검증. ([2026-06-18 accessExpiredTime WEB 제거 반영]) |
| `web_login_without_clientId_redirects_to_defaultUrl` | `status().is3xxRedirection()` → `status().isOk()`. `response.getHeader("Location")` 검증 제거. `jsonPath("$.redirectUrl").value("http://localhost:3000")` 추가. |
| `web_login_unregistered_clientId_redirects_to_defaultUrl` | `status().is3xxRedirection()` → `status().isOk()`. `Location` 헤더 검증 제거. `jsonPath("$.redirectUrl").value("http://localhost:3000")` 추가. |
| `web_login_with_registered_clientId_single_redirect_uri` | `status().is3xxRedirection()` → `status().isOk()`. `response.getHeader("Location")` → `jsonPath("$.redirectUrl").value("https://app.example.com/callback")`. |
| `web_login_with_registered_clientId_multiple_redirect_uris` | `status().is3xxRedirection()` → `status().isOk()`. `response.getHeader("Location")` → `jsonPath("$.redirectUrl").value("https://a-app.example.com/callback")`. |
| `web_login_location_does_not_contain_tokens` | `status().is3xxRedirection()` → `status().isOk()`. `location` 변수 및 `doesNotContain("accessToken"/"refreshToken"/"at="/"rt=")` 검증 → `body`(응답 JSON 문자열)가 토큰 값을 포함하지 않음으로 대체. `jsonPath("$.accessToken")` 미포함, `jsonPath("$.refreshToken")` 미포함. |

**AppLoginTest 내 수정 대상 테스트 (1건)**:

| 테스트 메서드 | 변경 내용 |
|---|---|
| `web_login_response_does_not_contain_redirectUrl_in_body` | 기존 전제("WEB은 302, body 없음")가 무효화됨. **삭제하거나** 테스트 내용을 "WEB 로그인 시에도 body에 redirectUrl이 포함됨"으로 의미 반전. 권장: 삭제 후 `WebLoginTest`에 동등 검증 포함. |

**추가할 WEB 검증 항목** ([2026-06-18 accessExpiredTime WEB 제거 반영]):
  - body에 `accessToken`/`refreshToken`/`accessExpiredTime` 미포함 (`@JsonInclude(NON_NULL)`)
  - `redirectUrl` 필드 값이 올바른 URL
  - `accessExpiredTime` 필드는 WEB body에 존재하지 않음 (미포함 검증)

- **적용 컨벤션**:
  - `@Nested` + `@DisplayName` 한글 테스트명 (`docs/CONVENTION.md` 5.1절)
  - Given-When-Then 주석 구분 (`docs/CONVENTION.md` 5.2절)
  - `jsonPath` 검증 사용 (기존 패턴 그대로)
- **연관 todo**:
  - `[ ] AuthApiIntegrationTest WEB 분기 테스트 수정`
  - `[ ] AuthApiIntegrationTest 내 WEB 분기 검증 항목 추가`
  - `[ ] AuthApiIntegrationTest APP 분기 테스트 중 WEB 비교 케이스 수정`

---

### 호출 흐름

정상 경로 (WEB 분기, 변경 후):
```
POST /api/v1/auth/login (Client-Type: WEB 또는 헤더 없음)
  → JsonLoginAuthenticationFilter.attemptAuthentication()
      objectMapper.readValue(InputStream) → LoginRequest(loginId, password, clientId)
      request.setAttribute("clientId", clientId)
      DaoAuthenticationProvider → BCrypt 검증
  → JsonLoginAuthenticationFilter.successfulAuthentication()
      loginTokenUseCase.issue(member) → TokenPair(accessToken, refreshToken, accessExpiredAt)
      [isApp = false 분기]
      1. cookieManager.setAtCookie(response, tokens.accessToken())   ← Set-Cookie: at HttpOnly
      2. cookieManager.setRtCookie(response, tokens.refreshToken())  ← Set-Cookie: rt HttpOnly
      3. clientId = request.getAttribute("clientId")
      4. target = loginRedirectUseCase.resolve(clientId, defaultRedirectUrl)
      5. response.setStatus(SC_OK)
      6. response.setContentType(APPLICATION_JSON_VALUE)
      7. response.setCharacterEncoding("UTF-8")
      8. objectMapper.writeValue(response.getWriter(),
             LoginResponse.web(target))
             // [2026-06-18 accessExpiredTime WEB 제거 반영]: tokens.accessExpiredAt() 전달 불필요
  → HTTP 200 OK
     Set-Cookie: at=...; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=3600
     Set-Cookie: rt=...; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=2592000
     Content-Type: application/json
     Body: { "redirectUrl": "<url>" }
     (accessToken, refreshToken, accessExpiredTime 필드는 null → @JsonInclude(NON_NULL)로 JSON 제외)
```

정상 경로 (APP 분기, 변경 없음):
```
  → [isApp = true 분기]
      loginRedirectUseCase.resolve(clientId, defaultRedirectUrl) → redirectUrl
      LoginResponse.app(accessToken, accessExpiredAt, refreshToken, redirectUrl)
  → HTTP 200 OK
     Body: { "accessToken": "...", "accessExpiredTime": ..., "refreshToken": "...", "redirectUrl": "..." }
```

예외 / 실패 경로:
```
[인증 실패]
  → unsuccessfulAuthentication() (변경 없음)
  → HTTP 401 { "errorCode": "INVALID_CREDENTIALS", ... }

[loginRedirectUseCase.resolve() 내부 분기 — WEB·APP 공통, 변경 없음]
  clientId 없음 또는 null               → defaultRedirectUrl (fail-safe)
  clientId 있으나 InvalidClientException → defaultRedirectUrl (fail-safe)
  redirect_uri Set 비어있음              → defaultRedirectUrl (fail-safe)
  기타 RuntimeException (DB 오류 등)    → defaultRedirectUrl (fail-safe)
  → 모든 케이스에서 4xx 거부 없이 진행, WEB은 200+body(redirectUrl=defaultRedirectUrl)

[objectMapper.writeValue() IOException]
  → IOException propagate (AbstractAuthenticationProcessingFilter 상위 처리)
  → 기존 APP 분기와 동일한 예외 경로
```

---

### 컨벤션 준수 항목

- **네이밍**: 변경 메서드 및 팩토리는 기존 camelCase 유지. `web(long, String)` 팩토리 접두사 없음 — 정적 팩토리 관례 그대로.
- **패키지 배치**: `JsonLoginAuthenticationFilter`는 `config/security/`에 유지. `LoginResponse`는 `presentation/dto/`에 유지. OpenAPI 커스터마이저는 `config/openapi/`에 유지. `presentation/filter` 패키지 사용 금지 규칙 위반 없음 (`docs/CONVENTION.md` 1.1절).
- **의존성 방향**: `config/security/` → `application.usecase` 인터페이스(`LoginRedirectUseCase`, `LoginTokenUseCase`)에만 의존. `presentation/dto/`는 외부 프레임워크(Jackson, Swagger)만 참조. 규칙 위반 없음 (`docs/ARCHITECTURE.md` 계층 모델).
- **불변성**: `LoginResponse` record 필드 불변 유지. 새 `web()` 팩토리도 `new LoginResponse(...)` 단일 호출로 생성.
- **Javadoc**: 수정되는 `public` 클래스/메서드 Javadoc 모두 갱신 필수. `@deprecated` 어노테이션 제거 시 Javadoc에서 `@deprecated` 태그도 제거 (`docs/CONVENTION.md` 4.1절).
- **import 규칙**: fully-qualified name 본문 직접 사용 금지 (예: `org.springframework.http.HttpServletResponse.SC_OK`). 상단 import 추가 후 짧은 이름 사용. `HttpServletResponse.SC_OK`는 이미 import된 `jakarta.servlet.http.HttpServletResponse`를 통해 접근하므로 신규 import 불필요.
- **JSON 직렬화**: `@JsonInclude(NON_NULL)` 클래스 레벨 선언 유지. WEB 응답에서 `accessToken`/`refreshToken` null 필드는 JSON에서 자동 제외 (`docs/CONVENTION.md` 2.4절).
- **테스트 패턴**: `@Nested` + `@DisplayName` 한글 + Given-When-Then + AssertJ + `@SpringBootTest`/MockMvc E2E 구조 (`docs/CONVENTION.md` 5.1–5.3절).
- **포맷팅**: 수정 후 `./gradlew format` 실행 필수 (Google Java Format 1.17.0, 탭 들여쓰기).

## 체크리스트
- [ ] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [ ] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [ ] 모든 구성 요소가 적용 컨벤션을 명시함
- [ ] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/JsonLoginAuthenticationFilter.java` — WEB 분기 103-112행 (변경 핵심)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/LoginResponse.java` — `web()` 팩토리 41-45행, `app(4인자)` 팩토리 63-71행 (미러링 참조)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/openapi/LoginOpenApiCustomizer.java` — `loginResponses()` 59-71행 (302 제거 대상)
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — `WebLoginTest` 전체, `AppLoginTest.web_login_response_does_not_contain_redirectUrl_in_body`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/presentation/dto/LoginResponseTest.java` — `AppFactoryMethodTest` (구조 미러링 기준)
- `docs/CONVENTION.md` — 1.1(패키지), 2.3(불변성), 2.4(JSON), 4.1(Javadoc), 5.1–5.3(테스트), 8.2/8.6/8.7(OpenAPI)
- `docs/ARCHITECTURE.md` — 계층 모델 및 의존성 규칙, [흐름 A] (갱신 대상)
- `docs/adr/0012-backend-decided-login-redirect.md` — amend 대상 (전달 방식 302→body 전환 보완 기재)
