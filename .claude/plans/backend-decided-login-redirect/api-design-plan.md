# backend-decided-login-redirect - api-design

## 메타
- **작업명**: backend-decided-login-redirect
- **문서 타입**: api-design
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

`POST /api/v1/auth/login` 단일 엔드포인트를 변경한다. 프로토콜은 기존과 동일하게 REST/HTTP이며, Spring Security `AbstractAuthenticationProcessingFilter`(JsonLoginAuthenticationFilter) 레이어에서 처리된다. 이전의 returnUrl 화이트리스트 방식은 폐기되고, WEB 클라이언트 요청 body에 `clientId`를 추가하여 백엔드가 해당 clientId에 등록된 `redirect_uri`를 직접 조회한 뒤 `302`로 보낸다. user-supplied URL이 없으므로 open redirect가 구조적으로 불가능하다. APP 클라이언트(`Client-Type: APP`) 동작은 변경 없다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 인증 / 권한 | 연관 todo |
|--------|------|------|-------------|-----------|
| POST | /api/v1/auth/login | 자격증명 검증 후 AT/RT 발급. WEB → 302+쿠키(clientId로 redirect_uri 조회), APP → 200+body | 인증 불필요 (퍼블릭) | API 작업 #1, #2, #3, #4, #5 |

---

### 엔드포인트 상세

#### POST /api/v1/auth/login

- **목적**: loginId/password로 자격증명 검증 후 AT·RT를 발급한다. WEB 클라이언트는 쿠키 세팅 후 `clientId`에 등록된 `redirect_uri`를 조회하여 302 리다이렉트한다. `clientId` 미전달·미등록·redirect_uri 없음 등 모든 fallback 케이스에서 `auth.redirect.default-url`로 302한다. APP 클라이언트는 기존과 동일하게 200 + body로 응답한다.

- **연관 todo**:
  - `[ ] POST /api/v1/auth/login 요청 body 스펙을 {loginId, password, clientId}로 변경 — 기존 {loginId, password}에 clientId 필드 추가 (WEB/APP 공통 수신, APP은 무시)` (API 작업 #1)
  - `[ ] WEB 클라이언트 로그인 성공 응답을 200 OK + body → 302 Found + Location으로 변경 — Set-Cookie(at, rt)는 기존과 동일하게 유지, body 없음` (API 작업 #2)
  - `[ ] clientId 미전달·미등록·등록 redirect_uri 없음 모두 auth.redirect.default-url로 302 fallback (4xx 거부 없음) — API 설계 문서에 명시` (API 작업 #3)
  - `[ ] APP 클라이언트(Client-Type: APP) 로그인 성공 응답은 기존 동작 유지 — 200 OK + body(AT, RT, accessExpiredAt), 리다이렉트 없음, clientId 파싱하되 사용하지 않음` (API 작업 #4)
  - `[ ] 토큰을 절대 Location URL 쿼리/프래그먼트에 포함하지 않음을 API 설계 문서에 명시` (API 작업 #5)

---

#### 요청 스펙

**`clientId`의 전달 방식: JSON body 필드 채택**

`JsonLoginAuthenticationFilter.attemptAuthentication()`은 `request.getInputStream()`으로 JSON body를 단일 소비한다. 기존 returnUrl 방식은 이 제약을 회피하기 위해 쿼리 파라미터를 사용했으나, clientId 방식에서는 `attemptAuthentication()` 단계에서 body를 파싱할 때 `clientId`를 함께 읽어 `request.setAttribute("clientId", loginRequest.clientId())`에 저장한다. `successfulAuthentication()` 시점에 `request.getAttribute("clientId")`로 꺼내 사용한다. InputStream 단일 소비 문제를 attribute 경유로 해결한다.

- **요청 헤더**:

  | 헤더 | 필수 | 값 | 설명 |
  |------|------|----|------|
  | `Content-Type` | 필수 | `application/json` | JSON body 파싱 필요 |
  | `Client-Type` | 선택 | `APP` 또는 생략 | 생략 시 WEB으로 간주 |

- **요청 바디**:

  | 필드 | 타입 | 필수 | 설명 |
  |------|------|------|------|
  | `loginId` | String | 필수 | 로그인 식별자 |
  | `password` | String | 필수 | 비밀번호 |
  | `clientId` | String | 선택 | OAuth 클라이언트 ID. WEB 전용(redirect_uri 조회). APP에서 수신해도 무시. 없으면 defaultUrl로 fallback |

  ```json
  {
    "loginId": "jongmin",
    "password": "P@ssw0rd!",
    "clientId": "econovation-web"
  }
  ```

- **요청 예시 (WEB — clientId 있음)**:
  ```
  POST /api/v1/auth/login
  Content-Type: application/json

  {
    "loginId": "jongmin",
    "password": "P@ssw0rd!",
    "clientId": "econovation-web"
  }
  ```

- **요청 예시 (WEB — clientId 없음, fallback)**:
  ```
  POST /api/v1/auth/login
  Content-Type: application/json

  {
    "loginId": "jongmin",
    "password": "P@ssw0rd!"
  }
  ```

- **요청 예시 (APP)**:
  ```
  POST /api/v1/auth/login
  Content-Type: application/json
  Client-Type: APP

  {
    "loginId": "jongmin",
    "password": "P@ssw0rd!",
    "clientId": "econovation-web"
  }
  ```

---

#### 응답 (성공 — WEB 클라이언트)

`Client-Type` 헤더가 없거나 `APP`이 아닌 모든 요청에 적용된다. 자격증명 검증 성공 후 AT·RT 쿠키를 세팅하고, `LoginRedirectResolver.resolve(clientId, defaultRedirectUrl)`가 반환한 URL로 302한다. 쿠키 세팅(`response.addHeader`)은 반드시 `response.sendRedirect()` 이전에 수행해야 한다 (`sendRedirect`가 응답을 커밋하므로 순서가 바뀌면 `Set-Cookie`가 누락됨).

**케이스 A: 유효한 clientId + redirect_uri 1개 등록**

- 상태: `302 Found`
- 헤더:
  ```
  Location: https://app.econovation.kr/callback
  Set-Cookie: at=<JWT>; HttpOnly; Secure; SameSite=None; Path=/; Max-Age=3600; Domain=.econovation.kr
  Set-Cookie: rt=<JWT>; HttpOnly; Secure; SameSite=None; Path=/; Max-Age=2592000; Domain=.econovation.kr
  ```
- 바디: 없음 (Content-Length: 0)
- `Location` 값: `ClientRedirectUriService.findByClientId(clientId).redirectUris()`에서 조회된 단일 URI

**케이스 B: 유효한 clientId + redirect_uri 여러 개 등록 (결정적 선택)**

- 상태: `302 Found`
- 헤더:
  ```
  Location: https://alpha.econovation.kr/callback
  Set-Cookie: at=<JWT>; HttpOnly; Secure; SameSite=None; Path=/; Max-Age=3600; Domain=.econovation.kr
  Set-Cookie: rt=<JWT>; HttpOnly; Secure; SameSite=None; Path=/; Max-Age=2592000; Domain=.econovation.kr
  ```
- 바디: 없음 (Content-Length: 0)
- `Location` 값: `redirectUris` Set을 알파벳 오름차순 정렬 후 첫 번째 URI. SAS `RegisteredClient`는 redirect_uri를 순서 비보장 `Set`으로 저장하므로, 정렬 없이 선택하면 호출마다 결과가 달라질 수 있다. 이 한계를 감수하고 정렬 첫 번째를 "대표 URI"로 취급한다.

**케이스 C: clientId 미전달 / 미등록(`InvalidClientException`) / redirect_uri 빈 Set — fallback**

- 상태: `302 Found`
- 헤더:
  ```
  Location: http://localhost:3000
  Set-Cookie: at=<JWT>; HttpOnly; Secure; SameSite=None; Path=/; Max-Age=3600; Domain=.econovation.kr
  Set-Cookie: rt=<JWT>; HttpOnly; Secure; SameSite=None; Path=/; Max-Age=2592000; Domain=.econovation.kr
  ```
- 바디: 없음 (Content-Length: 0)
- `Location` 값: `auth.redirect.default-url` 설정값 (기본: `http://localhost:3000`)
- `InvalidClientException`은 인증 실패가 아니다. 자격증명은 이미 성공했으므로 4xx 거부하지 않고 AT·RT 쿠키는 정상 발급한다. 목적지만 안전한 기본값으로 fallback한다.

> **보안 주의**: AT·RT는 절대 Location URL의 쿼리스트링이나 프래그먼트에 포함하지 않는다. 토큰 전달은 Set-Cookie(HttpOnly)로만 한다. `clientId`가 어떤 이유로든 유효한 redirect_uri를 산출하지 못하면 거부하지 않고 항상 defaultUrl로 302한다.

---

#### 응답 (성공 — APP 클라이언트)

`Client-Type: APP` 헤더가 있는 경우. 기존 동작을 완전히 유지한다. `clientId`를 body에서 파싱하되 사용하지 않는다.

- 상태: `200 OK`
- 헤더:
  ```
  Content-Type: application/json;charset=UTF-8
  ```
- 바디:
  ```json
  {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "accessExpiredTime": 1749340800000,
    "refreshToken": "eyJhbGciOiJSUzI1NiJ9..."
  }
  ```
  - `accessToken`: RS256 서명 AT JWT (Bearer 용)
  - `accessExpiredTime`: AT 만료 시각 (Unix epoch milliseconds)
  - `refreshToken`: RS256 서명 RT JWT

> APP에서 `clientId` 필드를 전달해도 무시한다. 리다이렉트 없음. `LoginResponse.app(accessToken, accessExpiredAt, refreshToken)` 정적 팩토리 메서드 사용 (기존과 동일).

---

#### 응답 (실패 — 자격증명 오류)

WEB/APP 공통. 기존 `unsuccessfulAuthentication()` 동작을 그대로 유지한다.

- 상태: `401 Unauthorized`
- 헤더:
  ```
  Content-Type: application/json;charset=UTF-8
  ```
- 바디:
  ```json
  {
    "errorCode": "INVALID_CREDENTIALS",
    "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
    "timestamp": "2026-06-07T12:00:00",
    "fieldErrors": null
  }
  ```

> 이 에러 응답 포맷은 `GlobalExceptionHandler`의 `ApiError` 레코드 구조(`errorCode`, `message`, `timestamp`, `fieldErrors`)와 동일하다. 단, 해당 필터는 Spring MVC 밖에서 동작하므로 `@RestControllerAdvice`가 개입하지 않고 직접 직렬화한다. `InvalidClientException`은 여기에 해당하지 않는다 — clientId 문제는 자격증명 실패가 아니므로 `unsuccessfulAuthentication()`을 거치지 않고 fallback 302로 처리된다.

---

#### 인증 / 권한

- **필요 여부**: 불필요. `SecurityConfig`에서 `/api/v1/auth/login`은 `permitAll()` 처리됨.
- **필요 역할/스코프**: 없음.
- **추가 조건**: 없음. 자격증명 자체가 인증 수단이므로 사전 인증 토큰 불필요.

---

#### clientId 분기 매트릭스 (WEB 전용)

`LoginRedirectResolver.resolve(clientId, defaultRedirectUrl)` 동작 정의:

| 조건 | `ClientRedirectUriService` 동작 | 최종 Location | 비고 |
|------|--------------------------------|---------------|------|
| `clientId`가 null | 조회 없이 즉시 반환 | defaultUrl | body에 `clientId` 필드 없는 경우 포함 |
| `clientId`가 빈 문자열("") | 조회 없이 즉시 반환 | defaultUrl | blank 포함 |
| `clientId`가 미등록 | `findByClientId()` → `InvalidClientException` | defaultUrl | 인증 실패 아님, 자격증명 성공 후 fallback |
| `clientId`가 등록됨 + redirect_uri 1개 | `ClientInfo.redirectUris()` size=1 | 해당 URI | 정렬 불필요 |
| `clientId`가 등록됨 + redirect_uri 여러 개 | `ClientInfo.redirectUris()` size>1 | 정렬(알파벳 오름차순) 후 첫 번째 | SAS Set 순서 비보장으로 인한 결정적 기준 적용 |
| `clientId`가 등록됨 + redirect_uri 빈 Set | `ClientInfo.redirectUris()` empty | defaultUrl | redirect_uri 없는 클라이언트 |

> `InvalidClientException`은 clientId 미등록 시 `ClientRedirectUriService.findByClientId()`가 던지는 예외 (`com.econo.auth.client.exception.InvalidClientException`). `LoginRedirectResolver`가 catch하여 defaultUrl로 반환한다. 이 예외가 필터 바깥으로 전파되지 않음을 명시한다.

---

#### 에러 / 엣지 케이스

| 상황 | WEB 동작 | APP 동작 |
|------|----------|----------|
| 자격증명 오류 | 401 + `INVALID_CREDENTIALS` body | 동일 |
| JSON body 파싱 실패 (malformed JSON) | 401 + `INVALID_CREDENTIALS` body (BadCredentialsException으로 처리) | 동일 |
| `clientId` 미전달 (필드 없음) | 쿠키 정상 발급 + 302 → defaultUrl | clientId 무시, 200 + body |
| `clientId` 빈 문자열 | 쿠키 정상 발급 + 302 → defaultUrl | clientId 무시, 200 + body |
| `clientId` 미등록 (`InvalidClientException`) | 쿠키 정상 발급 + 302 → defaultUrl (4xx 아님) | clientId 무시, 200 + body |
| `clientId` 등록 + redirect_uri 1개 | 쿠키 정상 발급 + 302 → 해당 URI | clientId 무시, 200 + body |
| `clientId` 등록 + redirect_uri 여러 개 | 쿠키 정상 발급 + 302 → 정렬 후 첫 번째 URI | clientId 무시, 200 + body |
| `clientId` 등록 + redirect_uri 빈 Set | 쿠키 정상 발급 + 302 → defaultUrl | clientId 무시, 200 + body |
| `auth.redirect.default-url` 미설정 | 환경변수 `REDIRECT_DEFAULT_URL` 없으면 `http://localhost:3000` 사용 | 해당 없음 |
| `ClientRedirectUriService.findByClientId()` 예외 (미등록 외 런타임 예외) | `LoginRedirectResolver`가 catch하여 defaultUrl로 302 (fail-safe) | 해당 없음 |
| Location URL에 토큰이 포함된 경우 | 해당 없음 — 토큰은 Set-Cookie 전용, Location은 redirect_uri 또는 defaultUrl만 사용 | 해당 없음 |

---

#### redirect_uri 복수 등록 한계 명시

SAS `RegisteredClient`는 redirect_uri를 `Set<String>`으로 저장하므로 삽입 순서가 보장되지 않는다. 복수 등록 시 `LoginRedirectResolver`는 알파벳 오름차순으로 정렬하여 첫 번째를 "대표 URI"로 선택한다. 이 선택 기준은 결정적(deterministic)이지만 비즈니스적으로 "주된" URI를 보장하지는 않는다. 클라이언트에 redirect_uri가 여러 개 등록된 경우 정렬 후 첫 번째가 로그인 redirect 대상이 됨을 운영자가 인지해야 한다.

---

#### 설정값 (application.yml)

```yaml
auth:
  # 역할: SAS 커스텀 로그인 페이지 경로 (경로 B 전용)
  # /oauth2/authorize 진입 시 미인증 상태에서 SPA 로그인 페이지로 리다이렉트하는 URL
  # 이 키는 LoginRedirectResolver와 무관하다
  frontend-login-url: ${FRONTEND_LOGIN_URL:http://localhost:3000/login}

  redirect:
    # 역할: 경로 A WEB 302 fallback 목적지
    # /api/v1/auth/login 로그인 성공 시 clientId 미전달·미등록·redirect_uri 없음일 때 사용하는 안전한 기본 목적지
    # auth.frontend-login-url과 역할이 다르므로 분리 관리
    default-url: ${REDIRECT_DEFAULT_URL:http://localhost:3000}
```

> `auth.frontend-login-url`과 `auth.redirect.default-url`의 역할 구분: 전자는 SAS가 미인증 `/oauth2/authorize` 요청을 SPA 로그인 페이지로 보내는 경로(경로 B), 후자는 JSON 로그인 API(경로 A)의 WEB 302 fallback이다. 둘 다 존치하되 용도 혼동을 방지한다.

---

## 체크리스트
- [x] todo의 모든 API 작업(#1~#5)이 엔드포인트로 명세됨
- [x] 각 엔드포인트의 인증/권한이 명시됨 (퍼블릭 엔드포인트임을 명확히 기술)
- [x] 모든 에러 케이스가 기존 에러 체계(`INVALID_CREDENTIALS`, `ApiError` 포맷)로 매핑됨 — `InvalidClientException`은 에러가 아닌 fallback으로 처리됨을 명시
- [x] 요청·응답 스키마가 실제 JSON 본문 예시로 작성됨
- [x] Set-Cookie 헤더 속성(HttpOnly, Secure, SameSite=None, Path, Max-Age, Domain)이 `TokenCookieManager` 실제 구현 기준으로 명시됨
- [x] `clientId`가 JSON body 필드이고 `request.setAttribute`를 통해 `successfulAuthentication`으로 전달되는 기술적 근거(InputStream 단일 소비) 명시됨
- [x] 토큰이 Location URL에 포함되지 않음을 명시됨
- [x] clientId 분기 매트릭스(5가지 케이스) 작성됨
- [x] redirect_uri 복수 등록 시 정렬 선택 기준 및 한계 명시됨
- [x] `InvalidClientException`이 인증 실패가 아닌 fallback임을 명시됨
- [x] `auth.frontend-login-url`과 `auth.redirect.default-url`의 역할 차이 명시됨

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — `attemptAuthentication`에서 InputStream 소비 및 `request.setAttribute("clientId", ...)` 저장 위치, `successfulAuthentication` WEB/APP 분기 확인
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ClientRedirectUriService.java` — `findByClientId(clientId)` 시그니처, `ClientInfo.redirectUris()` Set 반환, `InvalidClientException` 발생 조건
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/TokenCookieManager.java` — Set-Cookie 속성(at/rt, HttpOnly, SameSite=None, Secure, Path=/, MaxAge, Domain) 실제 구현
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/LoginResponse.java` — `LoginResponse.app()` APP 응답 포맷 (WEB의 `LoginResponse.web()`은 @Deprecated 처리됨)
- `services/apis/auth-api/src/main/resources/application.yml` — `auth.redirect.default-url`, `auth.frontend-login-url` 키 확인
- `docs/ARCHITECTURE.md` — 에러 코드 체계(`InvalidClientException` → `CLIENT_NOT_FOUND`), 인증 흐름 A 설명
- `docs/adr/0012-backend-decided-login-redirect.md` — 본 결정의 배경·근거 (clientId 기반으로 재작성 대상)
