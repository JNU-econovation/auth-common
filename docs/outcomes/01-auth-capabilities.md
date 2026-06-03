# 인증 시스템이 제공하는 것

이 문서는 auth-common이 구축한 인증 인프라가 **최종적으로 무엇을 제공하는지**를 정리한다.
왜 그렇게 결정했는지는 [ADR 문서](../adr/)를 참고하라.

---

## 한 줄 요약

> **한 번 로그인하면 `*.econovation.kr` 모든 서비스를 재로그인 없이 사용할 수 있다.**
> 새 서비스는 `econo-passport` 라이브러리 하나만 추가하면 인증 인프라를 그대로 쓸 수 있다.

---

## 사용자가 얻는 것

### SSO (Single Sign-On)
- `app.econovation.kr`에서 로그인하면 `eeos.econovation.kr`, `makers.econovation.kr` 등 모든 서브도메인에서 자동으로 인증됨
- 쿠키 `Domain=.econovation.kr`로 동작 — 사용자가 아무것도 할 필요 없음

### 로그인 방식

| 클라이언트 | 로그인 요청 | 토큰 위치 | 재발급 |
|---|---|---|---|
| **WEB (브라우저)** | `POST /api/v1/auth/login` + `Client-Type: WEB` | AT + RT → HttpOnly 쿠키 | RT 쿠키 자동 전송 |
| **APP (모바일/서버)** | `POST /api/v1/auth/login` + `Client-Type: APP` | AT + RT → 응답 body | body의 RT로 재발급 |

### 토큰 만료 정책

| 토큰 | 기본 만료 | 환경변수 |
|---|---|---|
| Access Token | **1시간** | `AT_EXPIRY_SECONDS` |
| Refresh Token | **30일** | `RT_EXPIRY_SECONDS` |

---

## 서비스 개발자가 얻는 것

### @PassportAuth 어노테이션
Gateway를 통해 들어온 요청에서 회원 정보를 한 줄로 꺼낼 수 있다.

```java
@GetMapping("/my-resource")
public ResponseEntity<?> get(@PassportAuth Passport passport) {
    Long memberId   = passport.getMemberId();    // 회원 PK
    String name     = passport.getName();         // 이름
    Integer gen     = passport.getGeneration();   // 기수
    String status   = passport.getStatus();       // AM/RM/CM/OB
    List<String> roles = passport.getRoles();     // ["USER"]
    boolean isAdmin = passport.isAdmin();         // ADMIN 여부
}
```

### 타 회원 정보 조회
자신의 Passport에는 본인 정보만 있다. 다른 회원의 이름·기수가 필요하면:

```
POST /api/v1/members/batch
Content-Type: application/json

{ "ids": [1, 2, 42] }
```

```json
[
  { "memberId": 1,  "name": "홍길동", "loginId": "hong", "generation": 30, "status": "AM" },
  { "memberId": 2,  "name": "김철수", "loginId": "kim",  "generation": 31, "status": "AM" },
  { "memberId": 42, "name": "이영희", "loginId": "lee",  "generation": 29, "status": "RM" }
]
```

- 단건도 동일한 엔드포인트 사용: `{ "ids": [42] }`
- 없는 ID는 결과에서 조용히 제외 (오류 아님)
- 최대 1,000개

### JWT 공개키 (JWKS)
서비스가 자체적으로 JWT를 검증해야 한다면:

```
GET /oauth2/jwks
→ { "keys": [{ "kty": "RSA", "kid": "econo-auth-rsa-key-v1", "n": "...", "e": "AQAB" }] }
```

---

## 운영자가 얻는 것

### OAuth 클라이언트 등록
새 서비스를 인증 시스템에 등록한다.

```
POST /api/v1/admin/clients

{
  "grantType": "authorization_code",
  "clientName": "신규 SPA",
  "redirectUris": ["https://new-app.econovation.kr/callback"]
}
→ { "clientId": "uuid-..." }
```

```
POST /api/v1/admin/clients

{
  "grantType": "client_credentials",
  "clientName": "배치 서비스",
  "upstreamUrl": "http://batch-service:8080",
  "pathPrefix": "/api/batch"
}
→ { "clientId": "uuid-...", "clientSecret": "one-time-secret", "routeId": "uuid-..." }
```

> clientSecret은 **1회만** 응답에 포함된다. 이후 재조회 불가.

### Gateway 라우트 조회

```
GET /api/v1/admin/routes
→ { "routes": [{ "routeId": "...", "clientId": "...", "upstreamUrl": "...", "pathPrefix": "..." }] }
```

---

## API 전체 목록

| 엔드포인트 | 메서드 | 인증 | 설명 |
|---|---|---|---|
| `/api/v1/auth/signup` | POST | 없음 | 회원 가입 |
| `/api/v1/auth/login` | POST | 없음 | 로그인 (WEB/APP) |
| `/api/v1/auth/reissue` | POST | RT | AT/RT 재발급 |
| `/api/v1/auth/logout` | POST | 없음 | 로그아웃 |
| `/api/v1/members/batch` | POST | Gateway | 회원 정보 일괄 조회 |
| `/oauth2/jwks` | GET | 없음 | RS256 공개키 |
| `/api/v1/admin/clients` | POST | 없음 (public) | OAuth 클라이언트 등록 |
| `/api/v1/admin/clients/{id}` | GET | Basic Auth (clientId:clientSecret) | 클라이언트 조회 |
| `/api/v1/admin/clients/{id}/redirect-uris` | POST/PUT/DELETE | Basic Auth (clientId:clientSecret) | redirectUri 관리 |
| `/api/v1/admin/routes` | GET | 없음 (public) | 라우트 목록 |
