# client-registration - api-design

## 메타
- **작업명**: client-registration
- **문서 타입**: api-design
- **작성일**: 2026-05-25
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

---

## 개요

SAS(Spring Authorization Server) 1.x 내장 OIDC Dynamic Client Registration(DCR) 기능을 활성화한다.
직접 엔드포인트를 구현하지 않으며, `AuthorizationServerConfig`에서 `clientRegistrationEndpoint`를 켜는 것으로 SAS가 RFC 7591(`POST /connect/register`) 및 RFC 7592 조회 엔드포인트를 자동으로 노출한다.
프로토콜은 HTTP REST이며, 엔드포인트 보호는 Bearer Access Token(scope: `client.create` / `client.read`)으로 수행된다.
registrar client(confidential, `client_credentials`)를 기존 `RegisteredClientConfig` 시드 패턴으로 추가하고, 2단계 흐름(토큰 발급 → 등록 호출)을 표준 운영 절차로 정의한다.

> **도메인 표기 기준**: 예시 도메인 `https://auth.econo.com`은 Gateway 공개 URL(= issuer)이다. auth-api는 Gateway 뒤에 위치하며 직접 외부 노출되지 않는다.

---

## 본문

### 엔드포인트 목록

| 구분 | 메서드 | 경로 | 설명 | 인증·권한 주체 | Gateway 통과 | 연관 todo |
|------|--------|------|------|--------------|------------|-----------|
| SAS 제공 | POST | `/oauth2/token` | registrar client_credentials → access_token 수령 (registrar 토큰 발급 흐름 1단계) | confidential client (client_secret_basic) | permit (기존 `/oauth2/` 접두사 커버) | API 작업 — registrar 토큰 발급 |
| SAS 제공 | POST | `/connect/register` | OIDC DCR — RFC 7591 신규 client 등록 | Bearer access_token (scope: `client.create`) | **신규 permit 추가 필요** (`/connect/` 접두사) | API 작업 #1 |
| SAS 제공 | GET | `/connect/register/{clientId}` | RFC 7592 — 등록 client 메타데이터 조회 | Bearer registration_access_token | **신규 permit 추가 필요** | API 작업 #2 |
| SAS 제공 | PUT | `/connect/register/{clientId}` | RFC 7592 — 등록 client 메타데이터 갱신 | Bearer registration_access_token | **신규 permit 추가 필요** | API 작업 #2 ⚠️ SAS 지원 여부 확인 필요 |
| SAS 제공 | DELETE | `/connect/register/{clientId}` | RFC 7592 — 등록 client 삭제 | Bearer registration_access_token | **신규 permit 추가 필요** | API 작업 #2 ⚠️ SAS 지원 여부 확인 필요 |

> **RFC 7592 지원 범위 플래그**: SAS 1.x가 RFC 7592의 GET(조회)을 지원하는 것은 문서상 확인되나, PUT(갱신)·DELETE(삭제)의 지원 여부는 **개발 단계에서 `OidcClientRegistrationEndpointFilter` 소스코드 및 SAS 공식 가이드(https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html) 현행 버전으로 반드시 재확인**해야 한다. 미지원이 확인될 경우 이번 범위에서 제공 가능한 DCR 기능은 **등록(POST) + 조회(GET)** 에 한정된다.

---

### registrar 토큰 발급 흐름 (2단계 운영 절차)

DCR 엔드포인트는 무단 등록을 막기 위해 인가된 access token을 요구한다. 운영자(또는 CI/CD 파이프라인)는 아래 2단계 절차를 따른다.

```
[1단계] registrar client → POST /oauth2/token
        grant_type=client_credentials
        scope=client.create
        Authorization: Basic base64(REGISTRAR_CLIENT_ID:REGISTRAR_CLIENT_SECRET)

        ← 200 OK
          { "access_token": "<token>", "token_type": "Bearer", "expires_in": 300, "scope": "client.create" }

[2단계] 운영자 → POST /connect/register
        Authorization: Bearer <access_token from 1단계>
        Content-Type: application/json
        { ... client metadata ... }

        ← 201 Created
          { "client_id": "...", "client_secret": "...", "registration_access_token": "...", ... }
```

> registrar client는 `RegisteredClientConfig`에서 고정 UUID `00000000-0000-0000-0000-000000000002`로 시드된다. 이 client 자체는 사용자 동의가 불필요하며(`requireAuthorizationConsent(false)`), `client_credentials` 그랜트만 허용된다.

---

### 엔드포인트 상세

---

#### POST `/oauth2/token` — registrar client_credentials 흐름 (1단계)

- **목적**: registrar client가 `client.create` 스코프 access token을 발급받아 `/connect/register` 호출 권한을 획득한다.
- **연관 todo**: `[ ] registrar client access token으로 /connect/register에 접근 가능한지 인가 검증`
- **요청 헤더**:

  ```
  POST /oauth2/token HTTP/1.1
  Content-Type: application/x-www-form-urlencoded
  Authorization: Basic cmVnaXN0cmFyOjxzZWNyZXQ+   (base64(clientId:clientSecret))
  ```

- **요청 바디**:

  ```
  grant_type=client_credentials&scope=client.create
  ```

  | 파라미터 | 필수 | 값 |
  |---------|------|-----|
  | `grant_type` | Y | `client_credentials` |
  | `scope` | Y | `client.create` (등록 전용) 또는 `client.read` (조회 전용) 또는 두 스코프 모두 |

- **응답 (성공)**:
  - `200 OK`
  - `Content-Type: application/json`

  ```json
  {
    "access_token": "<opaque-or-jwt>",
    "token_type": "Bearer",
    "expires_in": 300,
    "scope": "client.create"
  }
  ```

  > access token 만료 시간(300초 = 5분)은 registrar client의 `TokenSettings`에서 설정한다. 짧게 설정하여 탈취 시 피해를 최소화한다. 정확한 TTL은 implementation-plan에서 결정.

- **응답 (에러)**:
  - SAS가 OAuth 표준 에러를 처리. `GlobalExceptionHandler` 개입 없음.
  - `401 Unauthorized`

  ```json
  { "error": "invalid_client", "error_description": "..." }
  ```

  | 에러 코드 | 발생 조건 |
  |----------|---------|
  | `invalid_client` | client_id 미등록 또는 client_secret 불일치 |
  | `invalid_scope` | registrar client에 등록되지 않은 scope 요청 |
  | `unauthorized_client` | client_credentials grant 미허용 |

- **인증·권한**:
  - 필요 여부: HTTP Basic 인증 (`client_secret_basic`) 필수
  - 인증 방식: `Authorization: Basic base64(REGISTRAR_CLIENT_ID:REGISTRAR_CLIENT_SECRET)`
- **Gateway 라우팅**: 기존 `sas-oauth2` 라우트 (`/oauth2/` 접두사 startsWith 매칭)로 이미 커버됨. permit 목록도 `/oauth2/` 접두사로 이미 등록되어 있어 추가 변경 불필요.
- **비고**: 이 엔드포인트는 기존 `sas-authorization-server` 명세의 `POST /oauth2/token`과 동일하다. registrar client가 추가되어도 SAS가 동일 엔드포인트에서 처리하므로 별도 엔드포인트가 아님.

---

#### POST `/connect/register`

- **목적**: RFC 7591 OIDC DCR — 신규 OAuth 2.0 / OIDC client를 동적으로 등록한다.
- **연관 todo**: `[ ] POST /connect/register 엔드포인트 활성화 확인` / `[ ] POST /connect/register 및 관리 엔드포인트에 대한 CORS 정책 결정·적용`
- **요청 헤더**:

  ```
  POST /connect/register HTTP/1.1
  Content-Type: application/json
  Authorization: Bearer <access_token with scope=client.create>
  ```

- **요청 바디** — confidential client 등록 예시:

  ```json
  {
    "client_name": "My Third-Party App",
    "redirect_uris": ["https://myapp.example.com/callback"],
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "client_secret_basic",
    "scope": "openid profile"
  }
  ```

- **요청 바디** — public client 등록 예시:

  ```json
  {
    "client_name": "My Public SPA",
    "redirect_uris": ["https://spa.example.com/callback"],
    "grant_types": ["authorization_code"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "none",
    "scope": "openid profile"
  }
  ```

  | 필드 | 필수 | 설명 |
  |------|------|------|
  | `redirect_uris` | Y | 사전 등록할 리다이렉트 URI 목록. 최소 1개 이상 |
  | `grant_types` | N | 기본값: `["authorization_code"]`. SAS가 지원하는 값: `authorization_code`, `refresh_token`, `client_credentials` |
  | `response_types` | N | 기본값: `["code"]` |
  | `token_endpoint_auth_method` | N | `client_secret_basic`, `client_secret_post`, `none`. public client는 `none` |
  | `scope` | N | 공백 구분 스코프 문자열. 미지정 시 SAS 기본값 적용 |
  | `client_name` | N | 사람이 읽을 수 있는 client 이름 |

- **응답 (성공)**:
  - `201 Created`
  - `Content-Type: application/json`

  ```json
  {
    "client_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "client_secret": "generated-secret-value",
    "client_secret_expires_at": 0,
    "registration_access_token": "<opaque-token>",
    "registration_client_uri": "https://auth.econo.com/connect/register/a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "client_name": "My Third-Party App",
    "redirect_uris": ["https://myapp.example.com/callback"],
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "client_secret_basic",
    "scope": "openid profile"
  }
  ```

  > `client_secret`은 confidential client(`token_endpoint_auth_method != "none"`)인 경우에만 반환된다. public client 등록 시 `client_secret` 필드가 응답에 포함되지 않는다.

  > `client_secret_expires_at: 0`은 만료 없음을 의미한다 (RFC 7591 §3.2.1 규정).

  > `registration_access_token`은 이후 RFC 7592 관리 엔드포인트 호출에 사용한다. 이 토큰은 등록 응답에서만 반환되며 이후 재조회 불가이므로 안전하게 보관해야 한다.

- **응답 (에러)**:
  - SAS가 DCR 표준 에러를 처리. `GlobalExceptionHandler` 개입 없음.

  | HTTP 상태 | `error` | 발생 조건 |
  |----------|---------|---------|
  | 400 | `invalid_redirect_uri` | `redirect_uris`에 허용되지 않는 URI 포함 (예: localhost, http scheme 등 SAS 정책 위반) |
  | 400 | `invalid_client_metadata` | 메타데이터 필드 값이 유효하지 않음 (지원하지 않는 grant_type 등) |
  | 401 | `invalid_token` | `Authorization: Bearer` 헤더 없음 또는 access token 만료/무효 |
  | 403 | `insufficient_scope` | access token의 scope에 `client.create`가 없음 |

  에러 응답 바디 (SAS / RFC 7591 표준 형식):

  ```json
  {
    "error": "invalid_redirect_uri",
    "error_description": "One or more redirect_uris are not allowed."
  }
  ```

  > SAS DCR 에러 응답은 RFC 7591 §3.2.2 형식(`error` + `error_description`)을 따른다. 커스텀 `ApiError` 스키마(`errorCode`, `timestamp`, `fieldErrors`)와 형식이 다르다. `GlobalExceptionHandler`는 SAS 필터가 이미 응답을 완료한 이후 개입하지 않으므로 충돌이 발생하지 않는다 — 통합 테스트(`DcrIntegrationTest`)로 검증 필요.

- **인증·권한**:
  - 필요 여부: `Authorization: Bearer <access_token>` 필수
  - 필요 스코프: `client.create`
  - 토큰 발급 주체: registrar client (`client_credentials` 그랜트)
  - 스코프 보호 방식: SAS `clientRegistrationEndpoint`가 기본으로 `client.create` 스코프를 요구하는지, 아니면 `authorizationRuleCustomizer`로 명시적 등록이 필요한지 **개발 단계에서 SAS 공식 가이드 및 `OidcClientRegistrationEndpointFilter` 소스코드 현행 확인 필수**
- **DCR 등록 client의 기본 설정**:
  - `requireAuthorizationConsent`: `true` (3rd-party client이므로 사용자 동의 필수)
  - 동의 화면 UI: 외부 프런트엔드 담당이며 이번 백엔드 작업 범위 밖. 동의 화면 없이 `/oauth2/authorize` 흐름을 진행하면 SAS가 기본 동의 페이지(HTML)를 렌더링하거나 커스텀 URL로 리다이렉트한다 — consent 화면 처리 방식은 후속 작업으로 트래킹
  - DCR 등록 시 `ClientSettings.requireAuthorizationConsent(true)`를 기본값으로 주입하는 방법(SAS 커스터마이저 hook)은 **개발 단계에서 공식 문서 현행 확인 필수**
- **CORS 정책**:
  - `/connect/register`는 서버 간(운영자 스크립트 / CI 파이프라인) 호출이 주 사용 패턴이므로 CORS 허용 오리진을 `${CORS_ALLOWED_ORIGINS}` (기존 프런트 오리진)으로 제한하거나, 서버 간 호출 전용이라면 CORS 설정 없이 유지하는 것도 가능하다.
  - 정책 결정 기준: 백오피스 SPA에서 직접 DCR을 호출할 경우 `${CORS_ALLOWED_ORIGINS}`에 추가, 서버 간 호출만 허용할 경우 추가 불필요.
  - **이번 명세에서는 서버 간 호출 전용으로 가정하여 CORS 추가 없음으로 정한다.** 필요 시 implementation-plan에서 재결정.
- **Gateway 라우팅 영향**:
  - 기존 `GatewayRoutingConfig`에 `/connect/register`를 커버하는 라우트가 없다. `sas-connect` 라우트 및 permit 접두사(`/connect/`) 추가 필요.
  - 상세 내용은 아래 "Gateway 라우팅 영향" 섹션 참조.

---

#### GET `/connect/register/{clientId}`

- **목적**: RFC 7592 — 등록된 client의 메타데이터를 조회한다.
- **연관 todo**: `[ ] RFC 7592 관리 엔드포인트(GET /connect/register/{clientId}, PUT, DELETE) 활성화 확인`
- **⚠️ SAS 1.x 지원 여부**: GET(조회)은 SAS 문서에서 지원으로 확인됨. **개발 단계에서 SAS 1.x 현행 소스(`OidcClientRegistrationEndpointFilter`) 및 공식 가이드로 재확인 필수.**
- **요청 헤더**:

  ```
  GET /connect/register/a1b2c3d4-e5f6-7890-abcd-ef1234567890 HTTP/1.1
  Authorization: Bearer <registration_access_token>
  ```

- **요청 파라미터**: 없음 (clientId는 경로 파라미터)
- **응답 (성공)**:
  - `200 OK`
  - `Content-Type: application/json`

  ```json
  {
    "client_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "client_name": "My Third-Party App",
    "redirect_uris": ["https://myapp.example.com/callback"],
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "client_secret_basic",
    "scope": "openid profile",
    "registration_client_uri": "https://auth.econo.com/connect/register/a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }
  ```

  > `client_secret`은 조회 응답에 포함되지 않는다 (최초 등록 응답에서만 1회 반환).

- **응답 (에러)**:

  | HTTP 상태 | `error` | 발생 조건 |
  |----------|---------|---------|
  | 401 | `invalid_token` | `registration_access_token` 없음 또는 만료/무효 |
  | 403 | `insufficient_scope` | access token의 scope에 `client.read` 없음 |
  | 404 | `invalid_client_id` | 경로의 clientId에 해당하는 client 미존재 |

- **인증·권한**:
  - 필요 여부: `Authorization: Bearer <registration_access_token>` 필수
  - 토큰 종류: 등록 응답에서 반환된 `registration_access_token` (scope: `client.read`)
  - registrar access token(scope: `client.create`)으로 조회 가능한지는 SAS 내부 인가 규칙 확인 필요 — 별도 `registration_access_token` 사용이 RFC 7592 표준
- **Gateway 라우팅 영향**: `sas-connect` 라우트 추가로 커버됨 (아래 섹션 참조).

---

#### PUT `/connect/register/{clientId}` — ⚠️ SAS 1.x 지원 여부 미확인

- **목적**: RFC 7592 — 등록된 client 메타데이터 전체 갱신.
- **⚠️ SAS 1.x 지원 여부 확인 필요**: SAS 1.x가 PUT을 구현하는지 **개발 단계에서 `OidcClientRegistrationEndpointFilter` 소스코드 직접 확인 필수**. 미지원이 확인되면 이 엔드포인트는 이번 범위에서 제공하지 않으며, 관리 기능은 등록(POST) + 조회(GET)만 제공한다고 명시한다.
- **요청 헤더**:

  ```
  PUT /connect/register/a1b2c3d4-e5f6-7890-abcd-ef1234567890 HTTP/1.1
  Content-Type: application/json
  Authorization: Bearer <registration_access_token>
  ```

- **요청 바디** (RFC 7592 §2.2 — 전체 메타데이터 교체):

  ```json
  {
    "client_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "client_name": "My Third-Party App v2",
    "redirect_uris": ["https://myapp.example.com/callback", "https://myapp.example.com/callback2"],
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "client_secret_basic",
    "scope": "openid profile"
  }
  ```

- **응답 (성공)**:
  - `200 OK`
  - 바디: 갱신된 client 메타데이터 (GET 응답과 동일 형식)
- **응답 (에러)**: GET 에러 케이스와 동일 + `invalid_client_metadata` (400)
- **인증·권한**: GET과 동일 (`registration_access_token` 필수)

---

#### DELETE `/connect/register/{clientId}` — ⚠️ SAS 1.x 지원 여부 미확인

- **목적**: RFC 7592 — 등록된 client 삭제.
- **⚠️ SAS 1.x 지원 여부 확인 필요**: PUT과 동일. 미지원 시 이번 범위에서 제공하지 않는다.
- **요청 헤더**:

  ```
  DELETE /connect/register/a1b2c3d4-e5f6-7890-abcd-ef1234567890 HTTP/1.1
  Authorization: Bearer <registration_access_token>
  ```

- **요청 바디**: 없음
- **응답 (성공)**:
  - `204 No Content`
  - 바디 없음
- **응답 (에러)**: GET 에러 케이스와 동일
- **인증·권한**: GET과 동일 (`registration_access_token` 필수)

---

### Gateway 라우팅 영향

기존 `GatewayRoutingConfig`에는 `/connect/` 경로를 커버하는 라우트가 없다. 이번 작업에서 아래 변경이 필요하다.

#### 신규 라우트 추가

```java
.route("sas-connect", r -> r.path("/connect/**").uri(authApiUri))
```

기존 라우트와의 관계:

| 라우트 ID | 경로 패턴 | 상태 |
|----------|---------|------|
| `auth-api` | `/api/v1/auth/**` | 기존 유지 |
| `sas-oauth2` | `/oauth2/**` | 기존 유지 (registrar 토큰 발급도 커버) |
| `sas-well-known` | `/.well-known/**` | 기존 유지 |
| `sas-userinfo` | `/userinfo` | 기존 유지 |
| `sas-connect` | `/connect/**` | **신규 추가** |

#### permit 경로 추가

기존 `permittedPaths()`의 `startsWith` 매칭 방식을 그대로 사용한다.

```java
public List<String> permittedPaths() {
    return List.of(
        "/api/v1/auth/signup",
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
        "/oauth2/",
        "/.well-known/",
        "/userinfo",
        "/connect/"   // 신규 추가 — DCR 엔드포인트는 SAS 내부에서 Bearer 토큰으로 자체 인가 처리
    );
}
```

> `/connect/` 경로를 Gateway permit으로 지정하는 이유: DCR 엔드포인트의 인가(Bearer 토큰 검증 + 스코프 확인)는 SAS 필터체인이 전담한다. Gateway의 Passport 변환 필터가 개입하면 이중 인가가 발생하고, `registration_access_token`이 일반 사용자 JWT가 아니므로 Passport 변환이 실패한다.

#### CORS 설정

서버 간 호출 전용으로 가정하므로 `corsConfigurationSource()`에 `/connect/register` 경로 추가를 이번 범위에서는 보류한다. 백오피스 SPA에서 브라우저를 통해 직접 호출하는 요구사항이 생기면 아래 형식으로 추가한다:

```java
// 서버 간 호출 전용이면 추가 불필요
// SPA에서 직접 호출 시 아래 항목 추가
CorsConfiguration connectRegisterConfig = new CorsConfiguration();
connectRegisterConfig.addAllowedOrigin(corsAllowedOrigins);
connectRegisterConfig.addAllowedMethod("POST");
connectRegisterConfig.addAllowedMethod("GET");
connectRegisterConfig.addAllowedMethod("OPTIONS");
connectRegisterConfig.addAllowedHeader("*");
connectRegisterConfig.setAllowCredentials(false);
source.registerCorsConfiguration("/connect/register/**", connectRegisterConfig);
```

---

### OIDC Discovery 문서 변경

DCR 활성화 후 `GET /.well-known/openid-configuration` 응답에 `registration_endpoint` 필드가 추가된다.

```json
{
  "issuer": "https://auth.econo.com",
  "authorization_endpoint": "https://auth.econo.com/oauth2/authorize",
  "token_endpoint": "https://auth.econo.com/oauth2/token",
  "jwks_uri": "https://auth.econo.com/oauth2/jwks",
  "userinfo_endpoint": "https://auth.econo.com/userinfo",
  "registration_endpoint": "https://auth.econo.com/connect/register",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "refresh_token", "client_credentials"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"],
  "scopes_supported": ["openid", "profile"],
  "token_endpoint_auth_methods_supported": ["none", "client_secret_basic"],
  "code_challenge_methods_supported": ["S256"]
}
```

> `registration_endpoint` 필드는 SAS가 `clientRegistrationEndpoint` 활성화 시 자동으로 Discovery 문서에 추가한다. 별도 커스터마이징 불필요. DCR 활성화 전후의 Discovery 응답 변화를 통합 테스트(`DcrIntegrationTest` 시나리오 외)로 검증 권장.

---

### 에러 응답 체계 정리

#### SAS DCR 표준 에러 (RFC 7591/7592)

SAS 필터체인이 직접 처리. `GlobalExceptionHandler` 개입 없음.

```json
{
  "error": "<error_code>",
  "error_description": "<human-readable description>"
}
```

| 에러 코드 | HTTP 상태 | 발생 조건 |
|----------|----------|---------|
| `invalid_redirect_uri` | 400 | redirect_uris 정책 위반 |
| `invalid_client_metadata` | 400 | 지원하지 않는 grant_type, auth_method 등 메타데이터 무효 |
| `invalid_client_id` | 404 | RFC 7592 경로의 clientId 미존재 |
| `invalid_token` | 401 | Bearer 토큰 없음, 만료, 무효 |
| `insufficient_scope` | 403 | 필요 스코프 없음 (`client.create` / `client.read`) |

#### 커스텀 에러 (`ApiError`)

DCR 흐름 중 커스텀 에러가 발생하는 경우는 없다. DCR 요청은 SAS 필터가 선점하여 처리하며 `@RestController` 레이어에 도달하지 않는다.

#### 기존 `GlobalExceptionHandler` 충돌 여부

`/connect/register` 엔드포인트는 SAS `OidcClientRegistrationEndpointFilter`가 처리한다. 이 필터는 Spring MVC DispatcherServlet 이전에 응답을 완료하므로 `@RestControllerAdvice`인 `GlobalExceptionHandler`가 개입하지 않는다. 단, SAS가 처리하지 못한 예외(예: DB 연결 오류)가 상위로 전파될 경우 `handleGeneric`이 개입하여 `ApiError("INTERNAL_SERVER_ERROR", ...)` 형식으로 응답한다 — 이 경우 RFC 형식과 달라질 수 있으므로 통합 테스트로 확인 필요.

---

### registrar client 스펙 요약

| 속성 | 값 |
|------|-----|
| 고정 UUID | `00000000-0000-0000-0000-000000000002` |
| `clientId` | `${REGISTRAR_CLIENT_ID:registrar}` |
| `clientSecret` | `${REGISTRAR_CLIENT_SECRET}` (BCrypt 인코딩) |
| `clientAuthenticationMethod` | `client_secret_basic` |
| `authorizationGrantType` | `client_credentials` |
| `scope` | `client.create`, `client.read` |
| `requireAuthorizationConsent` | `false` (운영자 client — consent 불필요) |
| `requireProofKey` | `false` (client_credentials는 PKCE 없음) |
| 등록 방식 | 기존 `firstPartyPublicClient()` 패턴 적용. `findByClientId` 후 없으면 `save` (멱등) |

---

## 체크리스트
- [x] todo의 모든 API 작업이 엔드포인트로 명세됨
- [x] 각 엔드포인트의 인증·권한이 명시됨 (기본값 의존 X, 불확실 항목은 플래그 처리)
- [x] 모든 에러 케이스가 기존 에러 체계(SAS RFC 7591/7592 표준 에러, ApiError)로 매핑됨
- [x] 요청·응답 스키마가 실제 JSON 본문 예시로 작성됨
- [x] Gateway 라우팅 영향 (`sas-connect` 라우트, `/connect/` permit 추가)이 명시됨
- [x] registrar 토큰 발급 2단계 흐름이 명시됨
- [x] RFC 7592 PUT·DELETE SAS 1.x 지원 여부 미확인 플래그가 명시됨
- [x] DCR 등록 client `requireAuthorizationConsent(true)` 기본값 주입 방법 확인 필요 플래그 명시됨
- [x] `clientRegistrationEndpoint` 스코프 인가 규칙 기본값 확인 필요 플래그 명시됨
- [x] OIDC Discovery `registration_endpoint` 추가 영향 명시됨
- [x] consent 화면 UI 의존성이 이번 범위 밖임을 명시됨

---

## 미결 사항 (implementation-plan 및 개발 단계에서 결정 필요)

1. **RFC 7592 PUT·DELETE SAS 1.x 지원 여부** — `OidcClientRegistrationEndpointFilter` 소스코드 및 SAS 공식 가이드 현행 버전으로 확인 후 지원 범위 확정. 미지원 시 이번 범위는 POST(등록) + GET(조회)에 한정.

2. **`clientRegistrationEndpoint` 스코프 인가 규칙 기본값** — SAS가 `client.create` 스코프를 자동으로 요구하는지, `authorizationRuleCustomizer`로 명시적 등록이 필요한지 확인.

3. **DCR 등록 client `ClientSettings` 기본값 주입** — `requireAuthorizationConsent(true)` 및 기타 `ClientSettings` 기본값을 DCR 등록 시 주입하는 방법(SAS hook / 커스터마이저) 공식 문서 확인.

4. **registrar access token TTL** — `client_credentials` 토큰 만료 시간. 보안상 짧게(300초 내외) 설정 권장. `TokenSettings`에서 구체적 값 결정.

5. **`registration_access_token` 영속화 구조** — SAS DCR이 발급한 `registration_access_token`이 기존 `oauth2_authorization` 테이블에 저장되는지, 별도 컬럼/테이블이 필요한지 `JdbcOAuth2AuthorizationService` 소스코드 확인 (db-design-plan에서 다룸).

6. **CORS 정책 확정** — `/connect/register` 경로를 서버 간 호출 전용으로 유지할지, 백오피스 SPA 브라우저 호출도 지원할지 확정 후 `corsConfigurationSource()` 수정 여부 결정.

---

## 참고
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthorizationServerConfig.java`
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java`
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`
- `.claude/plans/sas-authorization-server/api-design-plan.md` — 직전 작업 API 설계 컨벤션 참조
- SAS 1.x OIDC Dynamic Client Registration 가이드: https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html
- RFC 7591 (OAuth 2.0 Dynamic Client Registration): https://datatracker.ietf.org/doc/html/rfc7591
- RFC 7592 (OAuth 2.0 Dynamic Client Registration Management): https://datatracker.ietf.org/doc/html/rfc7592
