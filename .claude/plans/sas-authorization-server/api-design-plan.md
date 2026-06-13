# sas-authorization-server - api-design

## 메타
- **작업명**: sas-authorization-server
- **문서 타입**: api-design
- **작성일**: 2026-05-24
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

---

## 개요

auth-api를 Spring Authorization Server(SAS) 1.x 기반 OIDC Authorization Server로 전환한다.
SAS가 표준 OAuth 2.1 / OIDC Core 1.0 엔드포인트를 자동 제공하고, 커스텀 JSON 로그인·회원가입·로그아웃 엔드포인트는 기존 REST 컨벤션(`@RestController`, `@RequestMapping`)을 유지한다.
api-gateway는 SAS 발급 JWT(RS256)를 JWKS URI로 검증하여 Passport 헤더로 변환하는 리소스 서버로 재작성된다.
프로토콜은 HTTP REST이며, Authorization Code + PKCE 그랜트를 사용하는 public client(자사 SPA)를 대상으로 한다.

> **도메인 표기 기준**: 본 문서의 예시 도메인 `https://auth.econo.com`은 **Gateway 공개 URL(= issuer)** 을 의미한다. auth-api는 Gateway 뒤에 있으며 직접 외부 노출되지 않는다.

---

## 본문

### 엔드포인트 목록

| 구분 | 메서드 | 경로 | 설명 | 인증·권한 주체 | Gateway 통과 | 연관 todo |
|------|--------|------|------|--------------|------------|-----------|
| SAS 제공 | GET | `/oauth2/authorize` | Authorization Code 플로우 진입점 | 브라우저(세션 쿠키) | permit (라우팅 통과) | API 작업 #3 |
| SAS 제공 | POST | `/oauth2/token` | 코드 → 토큰 교환 / Refresh | public client (PKCE) | permit (라우팅 통과) | API 작업 #4 |
| SAS 제공 | GET | `/oauth2/jwks` | RSA 공개키 JWK Set | 없음 (공개) | permit (라우팅 통과) | API 작업 #2 |
| SAS 제공 | GET | `/userinfo` | OIDC UserInfo | Bearer Access Token | permit (라우팅 통과) | API 작업 #5 |
| SAS 제공 | GET | `/.well-known/openid-configuration` | OIDC Discovery | 없음 (공개) | permit (라우팅 통과) | API 작업 #1 |
| 커스텀 | POST | `/api/v1/auth/login` | JSON 자격증명 → 서버 세션 수립 | 없음 (public) | permit | API 작업 #6 |
| 커스텀 | POST | `/api/v1/auth/signup` | 회원 가입 | 없음 (public) | permit | API 작업 #7 |
| 커스텀 | POST | `/api/v1/auth/logout` | 세션 무효화 | 세션 쿠키 (있으면 처리, 없으면 200) | permit | API 작업 #8 |

> **Gateway permit 정책**: SAS 표준 경로(`/oauth2/**`, `/.well-known/**`, `/userinfo`)와 커스텀 인증 경로(`/api/v1/auth/**`)는 모두 Bearer 토큰 검증 없이 통과시킨다.
> Gateway는 이 경로들을 auth-api(`${AUTH_API_URI}`)로 라우팅한다.

---

### SAS 제공 엔드포인트 명세

SAS 1.x 기본값 경로 기준으로 작성. 별도 코드 구현 불필요 — `AuthorizationServerConfig` 설정으로 활성화.

---

#### GET `/oauth2/authorize`

- **목적**: Authorization Code + PKCE 플로우 진입. 인증된 세션이 없으면 커스텀 JSON 로그인 페이지 URL로 리다이렉트한다.
- **연관 todo**: `[ ] GET /oauth2/authorize — SAS 자동 처리; 세션 인증 요구, 커스텀 JSON 로그인으로 리다이렉트 흐름 E2E 테스트`
- **인증·권한**:
  - 필요 여부: 서버 세션(Spring Security `SecurityContext`) 필요
  - 미인증 시 동작: `authenticationEntryPoint` → `302 Found` (아래 "authorize 진입점 정책" 참조)
  - 인가 동의: `requireAuthorizationConsent(false)` — 자사 1st-party client는 자동 승인
- **요청 파라미터** (Query String):

  ```
  GET /oauth2/authorize
    ?response_type=code
    &client_id=econo-spa
    &redirect_uri=https%3A%2F%2Fapp.econo.com%2Fcallback
    &scope=openid%20profile
    &code_challenge=<BASE64URL(SHA-256(code_verifier))>
    &code_challenge_method=S256
    &state=<opaque-random>
  ```

  | 파라미터 | 필수 | 설명 |
  |---------|------|------|
  | `response_type` | Y | `code` 고정 |
  | `client_id` | Y | 등록된 클라이언트 ID (`econo-spa`) |
  | `redirect_uri` | Y | 사전 등록된 URI와 일치해야 함 |
  | `scope` | Y | `openid profile` (공백 구분) |
  | `code_challenge` | Y | PKCE S256 챌린지 |
  | `code_challenge_method` | Y | `S256` |
  | `state` | 권장 | CSRF 방지용 opaque 값 |

- **응답 (성공 — 세션 인증 완료 시)**:
  - `302 Found`
  - `Location: https://app.econo.com/callback?code=<authorization_code>&state=<state>`

- **응답 (에러 — OAuth 에러)**:
  - SAS가 표준 OAuth 에러를 처리. `GlobalExceptionHandler` 개입 없음.
  - `302 Found` → `redirect_uri?error=<error_code>&error_description=<description>&state=<state>`

  | 에러 코드 | 발생 조건 |
  |----------|---------|
  | `invalid_request` | 필수 파라미터 누락, PKCE 미포함 |
  | `unauthorized_client` | `client_id` 미등록 또는 grant 미허용 |
  | `invalid_scope` | 미등록 scope 요청 |
  | `access_denied` | 사용자 인가 거부 |

- **비고**: 브라우저 기반 흐름. SPA는 이 URL을 직접 navigate해야 한다(fetch/XHR 불가).

---

#### POST `/oauth2/token`

- **목적**: Authorization Code를 Access Token / ID Token / Refresh Token으로 교환. 또는 Refresh Token으로 토큰 갱신.
- **연관 todo**: `[ ] POST /oauth2/token — SAS 자동 처리; OAuth2TokenCustomizer가 커스텀 클레임 주입하는지 통합 테스트`
- **인증·권한**:
  - 필요 여부: public client이므로 `client_secret` 없음 — `code_verifier`(PKCE)로 검증
  - CORS: 프런트 오리진에서 fetch로 직접 호출 가능해야 함 (아래 CORS 정책 참조)
- **요청 헤더**:

  ```
  POST /oauth2/token HTTP/1.1
  Content-Type: application/x-www-form-urlencoded
  Origin: https://app.econo.com
  ```

- **요청 바디 — 코드 교환**:

  ```
  grant_type=authorization_code
  &code=<authorization_code>
  &redirect_uri=https%3A%2F%2Fapp.econo.com%2Fcallback
  &client_id=econo-spa
  &code_verifier=<original_code_verifier>
  ```

- **요청 바디 — Refresh Token**:

  ```
  grant_type=refresh_token
  &refresh_token=<refresh_token>
  &client_id=econo-spa
  ```

- **응답 (성공)**:
  - `200 OK`
  - `Content-Type: application/json`

  ```json
  {
    "access_token": "<JWT>",
    "token_type": "Bearer",
    "expires_in": 3600,
    "refresh_token": "<opaque-or-jwt>",
    "scope": "openid profile",
    "id_token": "<JWT>"
  }
  ```

- **Access Token 클레임 구조** (커스텀 클레임 포함 — `PassportTokenCustomizer` 주입):

  ```json
  {
    "sub": "42",
    "iss": "https://auth.econo.com",
    "aud": ["econo-spa"],
    "iat": 1716523200,
    "exp": 1716526800,
    "scope": "openid profile",
    "memberId": 42,
    "loginId": "jongmin",
    "name": "김종민",
    "generation": 11,
    "status": "AM",
    "roles": ["USER"]
  }
  ```

  > `sub` 값은 `String(memberId)`로 설정. `PassportBuilder`가 `Long.valueOf(claims.getSubject())`로 파싱하므로 정합성 유지 필요.

- **ID Token 클레임 구조** (OIDC 표준 + 커스텀 클레임):

  ```json
  {
    "sub": "42",
    "iss": "https://auth.econo.com",
    "aud": ["econo-spa"],
    "iat": 1716523200,
    "exp": 1716526800,
    "nonce": "<nonce-if-provided>",
    "memberId": 42,
    "loginId": "jongmin",
    "name": "김종민",
    "generation": 11,
    "status": "AM",
    "roles": ["USER"]
  }
  ```

- **응답 (에러)**:
  - SAS가 표준 OAuth 에러를 처리. `GlobalExceptionHandler` 개입 없음.
  - `400 Bad Request`

  ```json
  {
    "error": "invalid_grant",
    "error_description": "..."
  }
  ```

  | 에러 코드 | 발생 조건 |
  |----------|---------|
  | `invalid_grant` | code 만료/재사용, code_verifier 불일치 |
  | `invalid_client` | client_id 미등록 |
  | `invalid_request` | 필수 파라미터 누락 |
  | `invalid_scope` | 미등록 scope |

---

#### GET `/oauth2/jwks`

- **목적**: RSA 공개키를 JWK Set 형태로 노출. api-gateway가 시작 시 및 주기적으로 fetch하여 토큰 서명 검증에 사용.
- **연관 todo**: `[ ] GET /oauth2/jwks — SAS 자동 노출; 게이트웨이가 이 URL에서 공개키를 가져오도록 설정 연계 확인`
- **인증·권한**: 없음 (공개 엔드포인트)
- **응답 (성공)**:
  - `200 OK`
  - `Content-Type: application/json`

  ```json
  {
    "keys": [
      {
        "kty": "RSA",
        "use": "sig",
        "alg": "RS256",
        "kid": "<key-id>",
        "n": "<base64url-modulus>",
        "e": "AQAB"
      }
    ]
  }
  ```

- **비고**: `RsaKeyConfig`에서 `RSAKey`에 `kid`를 명시적으로 설정해야 key rotation 시 식별 가능. api-gateway는 `${AUTH_JWKS_URI}` 환경변수로 이 URL을 참조하며, **`AUTH_JWKS_URI`는 auth-api 내부 주소를 직접 가리킨다** (예: `http://auth-api:8081/oauth2/jwks`). Gateway를 경유하지 않는다 — 자기참조 루프 방지.

---

#### GET `/userinfo`

- **목적**: OIDC 표준 UserInfo 엔드포인트. Access Token의 subject에 해당하는 사용자 클레임 반환.
- **연관 todo**: `[ ] GET /userinfo — SAS 자동 노출; 반환되는 클레임 목록 테스트로 확인`
- **인증·권한**:
  - 필요 여부: `Authorization: Bearer <access_token>` 필수
  - 필요 scope: `openid` 포함 필요
- **요청 헤더**:

  ```
  GET /userinfo HTTP/1.1
  Authorization: Bearer <access_token>
  ```

- **응답 (성공)**:
  - `200 OK`

  ```json
  {
    "sub": "42",
    "name": "김종민",
    "memberId": 42,
    "loginId": "jongmin",
    "generation": 11,
    "status": "AM",
    "roles": ["USER"]
  }
  ```

  > 반환 클레임은 `PassportTokenCustomizer`가 ID Token에 주입한 클레임 집합과 동일하게 맞출 것을 권장한다. SAS 1.x에서 UserInfo 응답 커스터마이징은 별도 `OidcUserInfoMapper` 빈 등록으로 제어 가능 — develop 단계 현행 문서 확인 필요.

- **응답 (에러)**:
  - `401 Unauthorized` (Access Token 없거나 만료)

  ```
  WWW-Authenticate: Bearer error="invalid_token"
  ```

---

#### GET `/.well-known/openid-configuration`

- **목적**: OIDC Discovery. 클라이언트/리소스 서버가 issuer, 엔드포인트 URL, 지원 알고리즘 등을 자동 탐색.
- **연관 todo**: `[ ] GET /.well-known/openid-configuration — SAS 자동 노출; issuer-uri 설정값 매핑 통합 테스트`
- **인증·권한**: 없음 (공개 엔드포인트)
- **응답 (성공)**:
  - `200 OK`

  ```json
  {
    "issuer": "https://auth.econo.com",
    "authorization_endpoint": "https://auth.econo.com/oauth2/authorize",
    "token_endpoint": "https://auth.econo.com/oauth2/token",
    "jwks_uri": "https://auth.econo.com/oauth2/jwks",
    "userinfo_endpoint": "https://auth.econo.com/userinfo",
    "response_types_supported": ["code"],
    "grant_types_supported": ["authorization_code", "refresh_token"],
    "subject_types_supported": ["public"],
    "id_token_signing_alg_values_supported": ["RS256"],
    "scopes_supported": ["openid", "profile"],
    "token_endpoint_auth_methods_supported": ["none"],
    "code_challenge_methods_supported": ["S256"]
  }
  ```

  > `issuer` 값은 `${AUTH_ISSUER_URI}` 환경변수로 주입 (= Gateway 공개 URL, 예: `https://auth.econo.com`). Discovery 문서의 `jwks_uri`는 클라이언트용 공개 주소이므로 Gateway URL을 그대로 반영하는 것이 표준이다. api-gateway 내부에서 실제 JWKS fetch에는 `${AUTH_JWKS_URI}` (auth-api 내부 주소)를 별도로 사용하며, 두 호스트가 달라도 정상이다.

---

### 커스텀 엔드포인트 상세

---

#### POST `/api/v1/auth/login`

- **목적**: JSON 자격증명을 수신하여 member-auth 도메인(`MemberUserDetailsService` + BCrypt)으로 인증 후 서버 세션을 수립한다. 토큰(Access/ID/Refresh)은 이 엔드포인트에서 발급하지 않는다. 세션 수립 후 SPA는 `/oauth2/authorize`로 이동한다.
- **연관 todo**: `[ ] POST /api/v1/auth/login (재작성) — JSON 자격증명 → 서버 세션 수립 → 200 OK`
- **구현 방식**: `JsonLoginAuthenticationFilter`가 `POST /api/v1/auth/login` 요청을 가로채어 처리. `MemberController.login()` 메서드는 제거된다(필터가 선점).
- **요청 헤더**:

  ```
  POST /api/v1/auth/login HTTP/1.1
  Content-Type: application/json
  Origin: https://app.econo.com
  ```

- **요청 바디**:

  ```json
  {
    "loginId": "jongmin",
    "password": "P@ssword1"
  }
  ```

  | 필드 | 타입 | 제약 | 설명 |
  |------|------|------|------|
  | `loginId` | String | NotBlank | 영문자·숫자·`-_.`, 3~19자 |
  | `password` | String | NotBlank | 평문 비밀번호 |

- **응답 (성공)**:
  - `200 OK`
  - `Set-Cookie: SESSION=<session-id>; HttpOnly; SameSite=None; Secure; Path=/`
  - 바디 없음 (빈 200)

  > SPA는 이 응답의 Set-Cookie를 수신한 뒤 `GET /oauth2/authorize`로 이동한다.

- **응답 (에러)**:

  | HTTP 상태 | `errorCode` | `message` | 발생 조건 |
  |----------|------------|----------|---------|
  | 401 | `INVALID_CREDENTIALS` | 아이디 또는 비밀번호가 올바르지 않습니다. | loginId 미존재 또는 비밀번호 불일치 |
  | 400 | `VALIDATION_FAILED` | 요청 값이 올바르지 않습니다. | loginId·password 필드 누락/공백 |

  에러 응답 바디 (`ApiError` 스키마):

  ```json
  {
    "errorCode": "INVALID_CREDENTIALS",
    "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
    "timestamp": "2026-05-24T10:00:00",
    "fieldErrors": null
  }
  ```

- **인증·권한**:
  - 필요 여부: 없음 (public — SecurityConfig에서 permit)
  - CSRF: `CookieCsrfTokenRepository.withHttpOnlyFalse()` 사용. **`/api/v1/auth/login`만 CSRF 검증 제외** (SPA cross-origin fetch 대응). SAS 표준 엔드포인트(`/oauth2/**` 등)는 SAS 자체 처리.
- **세션 쿠키 정책** (아래 별도 섹션 참조):
  - `SameSite=None; Secure` — SPA(`app.econo.com`)와 auth-api(`auth.econo.com`)가 cross-origin이므로 필수
- **비고**: 로그인 실패 응답은 `JsonLoginAuthenticationFilter`의 `AuthenticationFailureHandler`에서 기존 `InvalidCredentialsException` 에러 구조를 재사용한다.

---

#### POST `/api/v1/auth/signup`

- **목적**: 신규 회원 가입. 기존 구현 유지.
- **연관 todo**: `[ ] POST /api/v1/auth/signup — 기존 구현 유지. 변경 없음`
- **요청 헤더**:

  ```
  POST /api/v1/auth/signup HTTP/1.1
  Content-Type: application/json
  ```

- **요청 바디**:

  ```json
  {
    "name": "김종민",
    "loginId": "jongmin",
    "password": "P@ssword1",
    "generation": 11,
    "status": "AM"
  }
  ```

  | 필드 | 타입 | 제약 | 설명 |
  |------|------|------|------|
  | `name` | String | NotBlank, 1~50자 | 이름 |
  | `loginId` | String | NotBlank, `^[a-zA-Z0-9\-_.]{3,19}$` | 로그인 ID |
  | `password` | String | NotBlank, 8~19자 | 대문자·소문자·숫자·특수기호 각 1자 이상 |
  | `generation` | Integer | NotNull, 1~99 | 기수 |
  | `status` | MemberStatus | NotNull | `AM`, `RM`, `CM`, `OB` 중 하나 |

- **응답 (성공)**:
  - `201 Created`
  - 바디 없음

- **응답 (에러)**:

  | HTTP 상태 | `errorCode` | 발생 조건 |
  |----------|------------|---------|
  | 409 | `MEMBER_ALREADY_EXISTS` | loginId 중복 |
  | 400 | `INVALID_PASSWORD_POLICY` | 비밀번호 정책 위반 |
  | 400 | `INVALID_LOGIN_ID_FORMAT` | loginId 형식 위반 |
  | 400 | `VALIDATION_FAILED` | Bean Validation 실패 |

- **인증·권한**: 없음 (public)

---

#### POST `/api/v1/auth/logout`

- **목적**: 서버 세션 무효화 및 세션 쿠키 만료 처리. 기존 JWT 쿠키 Max-Age=0 방식에서 전환.
- **연관 todo**: `[ ] POST /api/v1/auth/logout — 세션 무효화 + 세션 쿠키 만료 처리로 재작성`
- **요청 헤더**:

  ```
  POST /api/v1/auth/logout HTTP/1.1
  Cookie: SESSION=<session-id>
  ```

- **요청 바디**: 없음
- **응답 (성공)**:
  - `200 OK`
  - `Set-Cookie: SESSION=; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=0`
  - 바디 없음

  > 세션 쿠키가 없는 상태로 호출해도 `200 OK` 반환 (멱등 처리).

- **응답 (에러)**:

  | HTTP 상태 | `errorCode` | 발생 조건 |
  |----------|------------|---------|
  | 500 | `INTERNAL_SERVER_ERROR` | 세션 무효화 중 예기치 않은 오류 |

- **인증·권한**: 없음 (세션 유무와 관계없이 permit)
- **비고**: `HttpSession.invalidate()`는 세션이 이미 무효한 경우 `IllegalStateException`을 던지므로 null 체크 또는 try-catch 처리 필요.

---

### authorize 진입점 정책

미인증 브라우저가 `GET /oauth2/authorize`에 접근하면 SAS는 기본적으로 `/login`(HTML 폼)으로 리다이렉트하려 한다.
헤드리스 JSON 전용 서버이므로 이 동작을 `authenticationEntryPoint`를 통해 외부 SPA 로그인 URL로 변경한다.

**동작 흐름**:

```
브라우저
  → GET /oauth2/authorize?response_type=code&...
  ← 302 Found
      Location: https://app.econo.com/login
                  ?redirect_uri=https%3A%2F%2Fauth.econo.com%2Foauth2%2Fauthorize
                  %3Fresponse_type%3Dcode%26client_id%3Decono-spa%26...
```

**구현 방식**: `AuthorizationServerConfig`의 SAS 필터체인에 `exceptionHandling` 설정 추가:

```java
http.exceptionHandling(ex -> ex
    .authenticationEntryPoint((request, response, authException) -> {
        String originalUri = request.getRequestURI()
            + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        String loginUrl = frontendLoginUrl
            + "?redirect_uri=" + URLEncoder.encode(issuerUri + originalUri, StandardCharsets.UTF_8);
        response.sendRedirect(loginUrl);
    })
);
```

- `frontendLoginUrl`: `${auth.frontend-login-url}` 환경변수 (예: `https://app.econo.com/login`)
- `issuerUri`: `${AUTH_ISSUER_URI}` (예: `https://auth.econo.com`)
- SPA는 로그인 성공(`POST /api/v1/auth/login` → 200) 후 `redirect_uri` 쿼리 파라미터를 파싱하여 해당 URL로 navigate한다.

---

### CORS 정책

auth-api는 헤드리스 JSON 전용 서버이므로 외부 SPA 오리진에서의 CORS 요청을 허용해야 한다.

**허용 오리진**: `${auth.cors.allowed-origins}` 환경변수 (예: `https://app.econo.com`)

**적용 경로 및 설정**:

| 경로 패턴 | `allowedOrigins` | `allowedMethods` | `allowCredentials` | 설명 |
|----------|-----------------|-------------------|------------------|------|
| `/api/v1/auth/**` | 프런트 오리진 | `GET, POST, OPTIONS` | `true` | 로그인·회원가입·로그아웃 (세션 쿠키 포함) |
| `/oauth2/token` | 프런트 오리진 | `POST, OPTIONS` | `false` | 토큰 교환 (Bearer 없음, Credentials 불필요) |
| `/oauth2/authorize` | 프런트 오리진 | `GET, OPTIONS` | `true` | navigate 또는 preflight |
| `/userinfo` | 프런트 오리진 | `GET, OPTIONS` | `false` | UserInfo 조회 |
| `/.well-known/**` | `*` | `GET` | `false` | Discovery (공개) |
| `/oauth2/jwks` | `*` | `GET` | `false` | 공개키 (공개) |

**구현 방식**: `AuthorizationServerConfig` 및 `SecurityConfig`에서 `CorsConfigurationSource` 빈 등록 후 각 필터체인에 `.cors(cors -> cors.configurationSource(...))` 적용.

> `allowCredentials=true` 와 `allowedOrigins=*` 는 동시에 사용할 수 없다. 오리진을 명시적으로 지정해야 한다.

---

### 세션 쿠키 정책

`POST /api/v1/auth/login` 성공 시 Spring Session이 발급하는 세션 쿠키 설정.

| 속성 | 값 | 근거 |
|------|-----|------|
| 이름 | `SESSION` (Spring Session 기본값) | Spring Session 표준 |
| `HttpOnly` | `true` | XSS 방지 |
| `Secure` | `true` | HTTPS 전용 |
| `SameSite` | `None` | SPA(`app.econo.com`)와 auth-api(`auth.econo.com`)가 cross-origin이므로 필수 |
| `Domain` | 설정 안 함 (auth-api 호스트에 귀속) | 서브도메인 간 공유 불필요. auth-api가 세션을 단독 소유 |
| `Path` | `/` | 전체 경로 |
| `Max-Age` | 세션 유지 시간 (서버 세션 timeout과 동일) | 기본값: 1800초 (30분) |

**application.yml 설정**:

```yaml
server:
  servlet:
    session:
      cookie:
        same-site: none
        secure: true
        http-only: true
```

> `SameSite=None`은 반드시 `Secure=true`와 함께 사용해야 브라우저가 쿠키를 수락한다.

---

### api-gateway — 라우팅·검증·Passport 변환

#### 라우팅 설정

기존 `GatewayRoutingConfig`를 확장하여 SAS 엔드포인트 라우트를 추가한다.

```
// 신규 라우트 (auth-api로 전달)
/oauth2/**          → ${AUTH_API_URI}
/.well-known/**     → ${AUTH_API_URI}
/userinfo           → ${AUTH_API_URI}

// 기존 라우트 (유지)
/api/v1/auth/**     → ${AUTH_API_URI}
```

**permit 경로 목록** (Bearer 토큰 검증 건너뜀):

```java
List.of(
  "/api/v1/auth/signup",
  "/api/v1/auth/login",
  "/api/v1/auth/logout",
  "/oauth2/",          // /oauth2/** 전체
  "/.well-known/",     // /.well-known/** 전체
  "/userinfo"
)
```

> 기존 `permittedPaths()`는 `startsWith` 매칭을 사용하므로 `/oauth2/`와 `/.well-known/`으로 접두사 등록하면 하위 경로 전체가 자동으로 커버된다.

#### JWKS 기반 JWT 검증

**변경 전**: `JwtVerifier`가 `JWT_SECRET`(HMAC-SHA256)으로 서명 검증.
**변경 후**: `JwtVerifier`가 `${AUTH_JWKS_URI}`에서 RSA 공개키를 가져와 RS256 서명 검증.

**권장 구현 방식**: `spring-boot-starter-oauth2-resource-server` 라이브러리 도입 — JWKS URI 기반 자동 키 fetch, JWK rotation 지원, 캐시 내장.

```yaml
# api-gateway application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${AUTH_JWKS_URI:http://auth-api:8081/oauth2/jwks}
```

> **`AUTH_JWKS_URI`는 auth-api 내부 주소를 직접 가리킨다** (예: `http://auth-api:8081/oauth2/jwks`). Gateway 공개 URL을 가리키면 Gateway → Gateway 자기참조 루프가 발생하므로 반드시 내부 주소를 사용한다. 토큰의 `iss` 클레임 검증은 별도로 `AUTH_ISSUER_URI`(= Gateway 공개 URL, 예: `https://auth.econo.com`) 기준으로 수행되며, `jwk-set-uri` 호스트와 `issuer` 호스트가 달라도 정상이다.

> `jjwt` 3종(`jjwt-api`, `jjwt-impl`, `jjwt-jackson`) 의존성을 `api-gateway/build.gradle.kts`에서 제거하고, `spring-boot-starter-oauth2-resource-server` 로 교체한다. 구체적 방식은 implementation-plan에서 결정.

**키 갱신 전략**: `spring-security-oauth2-resource-server`의 `NimbusReactiveJwtDecoder`는 JWKS URI를 캐시하며, `kid` 미스 발생 시 자동으로 키를 재fetch한다. 별도 캐시 TTL 구현 불필요.

#### Bearer 토큰 추출 변경

**변경 전** (`JwtCookieToPassportFilter`): `auth_token` 쿠키에서 JWT 추출.
**변경 후** (`BearerToPassportFilter` 또는 클래스명 유지): `Authorization: Bearer <token>` 헤더에서 JWT 추출.

```java
// 변경 전
HttpCookie cookie = exchange.getRequest().getCookies().getFirst("auth_token");

// 변경 후
String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
String token = (authHeader != null && authHeader.startsWith("Bearer "))
    ? authHeader.substring(7) : null;
```

#### Passport 변환 — 클레임 매핑

`PassportBuilder.buildPassport()`가 SAS 발급 JWT 클레임을 Passport 필드로 매핑:

| JWT 클레임 (SAS 발급) | Passport 필드 | 변환 방식 |
|---------------------|-------------|---------|
| `sub` | `memberId` | `Long.valueOf(claims.getSubject())` (기존 유지) |
| `loginId` | `loginId` | 커스텀 클레임 직접 매핑 (기존 유지) |
| `name` | `name` | 커스텀 클레임 직접 매핑 (기존 유지) |
| `generation` | `generation` | 커스텀 클레임 직접 매핑 (기존 유지) |
| `status` | `status` | 커스텀 클레임 직접 매핑 (기존 유지) |
| `roles` | `roles` | 커스텀 클레임 직접 매핑 (기존 유지) |
| `iat` | `issuedAt` | `Instant` → `LocalDateTime` (기존 유지) |
| `exp` | `expiresAt` | `Instant` → `LocalDateTime` (기존 유지) |

> `PassportBuilder`의 클레임 키(`loginId`, `name`, `generation`, `status`, `roles`)는 `PassportTokenCustomizer`가 주입하는 키와 완전히 일치해야 한다. 키 이름이 달라지면 Passport 필드가 null이 된다.

> `spring-security-oauth2-resource-server`를 사용하면 `JwtVerifier`의 반환 타입이 `io.jsonwebtoken.Claims` → `org.springframework.security.oauth2.jwt.Jwt`로 바뀐다. `PassportBuilder`의 `buildPassport` 메서드 시그니처를 `Jwt` 파라미터로 변경해야 한다 — implementation-plan에서 구체화 필요.

#### Gateway 에러 응답

보호 대상 경로에서 토큰 없음 또는 검증 실패 시:

| 상황 | HTTP 상태 | 응답 방식 | 비고 |
|------|----------|---------|------|
| `Authorization: Bearer` 헤더 없음 (보호 경로) | `401 Unauthorized` | body 없음, 빈 응답 (기존 `rejectUnauthorized` 유지) | 기존 패턴 유지 |
| JWT 서명 검증 실패 | `401 Unauthorized` | body 없음 | |
| JWT 만료 | `401 Unauthorized` | body 없음 | |
| Passport 변환 중 예외 | `500 Internal Server Error` | body 없음 | `PassportBuilder.buildAndSerialize` RuntimeException |

> Gateway는 리액티브 환경(WebFlux)이므로 에러 응답 body를 JSON으로 내리려면 별도 `ErrorWebExceptionHandler` 구현이 필요하다. 현재 구현은 body 없이 상태 코드만 반환하며, 이 방식을 유지한다.

---

## 체크리스트

- [x] todo의 모든 API 작업 항목(#1~#8)이 엔드포인트로 명세됨
- [x] 각 엔드포인트의 인증·권한이 명시됨 (SAS 표준 / 커스텀 구분, 기본값 의존 없음)
- [x] 모든 에러 케이스가 기존 에러 체계(`ApiError`, OAuth 표준 에러)로 매핑됨
- [x] 요청·응답 스키마가 실제 JSON 본문 예시로 작성됨
- [x] 세션 쿠키 SameSite/Secure 정책 명시됨
- [x] CORS 정책 및 `allowCredentials` 경로별 명시됨
- [x] Gateway permit 경로 목록 및 Passport 클레임 매핑 명시됨
- [x] SAS 표준 에러(`error`/`error_description`)와 커스텀 에러(`ApiError`) 충돌 없음 명시됨

---

## 미결 사항 (implementation-plan에서 결정 필요)

1. **RSA 키 외부화 형식** — PEM 환경변수(`RSA_PRIVATE_KEY`/`RSA_PUBLIC_KEY`) vs keystore 파일(`KEYSTORE_PATH`+`KEYSTORE_PASSWORD`). 운영 배포 방식(Docker Secret / K8s Secret / 파일 마운트)에 따라 결정. `RsaKeyConfig` 구현 분기.

2. **세션 저장소** — in-memory(단일 인스턴스 한정) vs `spring-session-jdbc`(즉시 도입, `V3__create_spring_session_tables.sql` 마이그레이션 필요). Redis 미도입 상태이므로 JDBC 세션이 현실적 선택.

3. **`LoginUseCase`/`LoginService` 존치 여부** — `MemberUserDetailsService`로 통합 후 제거 시 auth-core 포트 구조 변경 수반. `TokenIssuer` 포트 제거 범위에 따라 결정.

4. **Gateway JWT 검증 라이브러리** — `spring-boot-starter-oauth2-resource-server` 도입(권장, 자동 키 rotation) vs `jjwt` 유지(JWKS fetch 직접 구현). `PassportBuilder` 시그니처 변경 범위에 영향.

5. **`OidcUserInfoMapper` 커스터마이징** — `/userinfo` 응답 클레임을 `PassportTokenCustomizer`와 동일하게 맞추려면 별도 빈 등록 필요 여부 — SAS 1.x 현행 문서 확인 필요.

6. **자동 동의 설정** — `RegisteredClient.requireAuthorizationConsent(false)` vs 커스텀 `OAuth2AuthorizationConsentService` — SAS 1.x 문서 확인 후 결정.

7. **`BearerToPassportFilter` 클래스명** — `JwtCookieToPassportFilter`에서 명칭 변경 여부 — 기능 변경(쿠키 → Bearer) 대비 이름 일관성 차원.

---

## 참고

- `docs/ARCHITECTURE.md` — 모듈 구조, 헥사고날 패키지, 에러 코드 체계
- `docs/CONVENTION.md` — 코드 컨벤션 (네이밍, Lombok, 예외 처리 패턴)
- `docs/INFRASTRUCTURE.md` — 환경 변수 목록 (이번 작업에서 갱신 대상)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java` — 기존 login/logout 구현 (재작성 대상)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — `ApiError` 스키마 정의 위치
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/JwtCookieToPassportFilter.java` — 필터 구조 참조 (재작성 대상)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — 라우팅·permit 목록 참조 (확장 대상)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/PassportBuilder.java` — 클레임 매핑 로직 (수정 대상)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — Passport 필드 구조
- Spring Authorization Server 1.x 레퍼런스 문서 (https://docs.spring.io/spring-authorization-server/reference/)
- Spring Authorization Server 공식 스키마 DDL (https://github.com/spring-projects/spring-authorization-server)
