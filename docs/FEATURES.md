# auth-common 기능 가이드

econovation 인증 인프라가 제공하는 기능을 정리한 문서입니다.
새 서비스를 연동하거나 기능을 추가할 때 참고하세요.

---

## 목차

1. [인증 흐름 한눈에 보기](#1-인증-흐름-한눈에-보기)
2. [기능 목록](#2-기능-목록)
3. [새 서비스 연동하기](#3-새-서비스-연동하기)
4. [프론트엔드 연동 가이드](#4-프론트엔드-연동-가이드)
5. [알려진 한계](#5-알려진-한계)

---

## 1. 인증 흐름 한눈에 보기

```
┌─────────────────────────────────────────────────────────┐
│                    전체 아키텍처                           │
└─────────────────────────────────────────────────────────┘

클라이언트 (브라우저 / 앱)
    │
    │  ① 로그인: POST /api/v1/auth/login
    ▼
┌─────────────────────┐
│     auth-api        │  ← 인증 서버 (JWT 발급)
│  :8081              │
│  /api/v1/auth/**    │
│  /oauth2/jwks       │
│  /api/v1/admin/**   │
└─────────────────────┘
    │  ② at 쿠키 + rt 쿠키 (WEB)  or  AT+RT body (APP)
    ▼
클라이언트 (at 쿠키 보관)
    │
    │  ③ API 호출 (at 쿠키 자동 전송)
    ▼
┌─────────────────────┐
│    api-gateway      │  ← 인증 게이트웨이 (JWT 검증)
│  :8082              │
│  BearerToPassportFilter:
│    at 쿠키 or Bearer → JWT 검증 → X-User-Passport 주입
└─────────────────────┘
    │  ④ X-User-Passport: Base64(사용자 정보)
    ▼
┌─────────────────────┐  ┌─────────────────────┐
│    EEOS-BE          │  │    Service B        │  ← 내부 서비스들
│  PassportFilter     │  │  PassportFilter     │
│  @PassportAuth ✅   │  │  @PassportAuth ✅   │
└─────────────────────┘  └─────────────────────┘
```

---

## 2. 기능 목록

### 🔐 로그인 / 인증

#### WEB 로그인 (브라우저)
```
POST /api/v1/auth/login
Header: Client-Type: WEB  (생략 시 기본값)
Body: {"loginId": "...", "password": "..."}

응답:
  Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=3600
  Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=2592000
  Body: {"accessExpiredTime": 1780242839681}
```
- AT, RT 모두 HttpOnly 쿠키로 발급 → JavaScript 접근 불가 (XSS 보호)
- `Domain=.econovation.kr` 설정 시 모든 서브도메인 공유 → **SSO 자동**
- AT 만료: 1시간 / RT 만료: 30일

#### APP 로그인 (모바일 / 서버)
```
POST /api/v1/auth/login
Header: Client-Type: APP
Body: {"loginId": "...", "password": "..."}

응답 Body:
  {"accessToken": "<JWT>", "accessExpiredTime": ..., "refreshToken": "<JWT>"}
```

### 🔄 토큰 재발급

#### WEB 재발급
```
POST /api/v1/auth/reissue
Header: Client-Type: WEB
(rt 쿠키 자동 첨부)

응답: 새 at + rt 쿠키 교체
```

#### APP 재발급
```
POST /api/v1/auth/reissue
Header: Client-Type: APP
Body: {"refreshToken": "<RT>"}

응답 Body: {"accessToken": ..., "refreshToken": ...}
```

### 🚪 로그아웃
```
POST /api/v1/auth/logout
Header: Client-Type: WEB

응답: at + rt 쿠키 Max-Age=0 삭제
```

### 👤 회원가입
```
POST /api/v1/auth/signup
Body: {
  "name": "홍길동",
  "loginId": "honggildong",
  "password": "Econo1234!",
  "generation": 30,
  "status": "AM"    // AM | RM | CM | OB
}
```

---

### 🛠️ OAuth 클라이언트 관리 (Admin)

등록(`POST /clients`) 및 라우트 조회(`GET /routes`)는 인증 불필요 (public).
클라이언트 조회 및 redirectUri 관리(4개 endpoint)는 `Authorization: Basic base64(clientId:clientSecret)` 헤더 필수.

#### 클라이언트 등록
```
POST /api/v1/admin/clients

// grantType 생략 → client_credentials 디폴트 (가장 단순)
{
  "clientName": "app-b"
}

// SPA/웹앱 (authorization_code 명시)
{
  "grantType": "authorization_code",
  "clientName": "EEOS 웹",
  "redirectUris": ["https://app.econovation.kr/callback"]
}

// 서버간 + 라우팅 등록
{
  "grantType": "client_credentials",
  "clientName": "새 서비스",
  "upstreamUrl": "http://new-service:8080",  // Gateway 라우팅 대상
  "pathPrefix": "/api/new"                    // 경로 접두사
}
```

#### redirectUri 관리 (인증: Basic Auth — clientId:clientSecret)
```
POST   /api/v1/admin/clients/{clientId}/redirect-uris  // 추가
DELETE /api/v1/admin/clients/{clientId}/redirect-uris  // 삭제
PUT    /api/v1/admin/clients/{clientId}/redirect-uris  // 전체 교체
GET    /api/v1/admin/clients/{clientId}                // 조회
```

---

### 🌐 Gateway 기능

#### JWT 검증
- Bearer 헤더 또는 `at` 쿠키에서 JWT 자동 추출
- JWKS 공개키로 RS256 서명 검증 (캐시 → 매 요청마다 auth-api 호출 없음)
- 검증 실패 시 401, 성공 시 X-User-Passport 헤더 주입

#### X-User-Passport 헤더
내부 서비스가 받는 헤더 (Base64 디코딩 시):
```json
{
  "memberId": 1,
  "loginId": "user01",
  "name": "홍길동",
  "generation": 30,
  "status": "AM",
  "roles": ["USER"],
  "issuedAt": "...",
  "expiresAt": "..."
}
```

#### 인증 불필요 경로
`/api/v1/auth/**`, `/oauth2/jwks`, `/api/health-check`, `/api/auth/**`, `/api/guest/**` 등

---

## 3. 새 서비스 연동하기

> 스킬 사용: `.claude/skills/register-service/SKILL.md`
> 또는 아래 수동 절차

### Step 1. auth-api에 클라이언트 등록

```bash
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -d '{
    "clientName": "내 새 서비스",
    "upstreamUrl": "http://my-service:8080",
    "pathPrefix": "/api/my-service"
  }'
# → clientId, clientSecret 반환 (clientSecret은 1회만 노출 — 반드시 즉시 저장)
```

### Step 2. Gateway 라우팅 추가

`services/apis/api-gateway/src/main/java/.../GatewayRoutingConfig.java`:

```java
.route("my-service",
    r -> r.path("/api/my-service/**")
        .filters(f -> f.removeRequestHeader("Authorization"))
        .uri("http://my-service:8080"))
```

### Step 3. 서비스에 econo-passport 추가

```kotlin
// build.gradle.kts
repositories { maven("https://jitpack.io") }
implementation("com.github.JNU-econovation:econo-passport:1.0.3")
```

Security 체인에 필터 등록:
```java
httpSecurity.addFilterBefore(new PassportAuthenticationFilter(), LogoutFilter.class);
```

### Step 4. 컨트롤러에서 사용

```java
@GetMapping("/api/my-service/data")
public ResponseEntity<?> getData(@PassportAuth Passport passport) {
    Long memberId = passport.getMemberId();
    String name = passport.getName();
    return ResponseEntity.ok(service.getData(memberId));
}
```

---

## 4. 프론트엔드 연동 가이드

### 로그인

```javascript
// WEB 로그인
const res = await fetch('https://gateway.econovation.kr/api/v1/auth/login', {
  method: 'POST',
  credentials: 'include',  // ← 쿠키 자동 저장/전송 필수
  headers: { 'Content-Type': 'application/json', 'Client-Type': 'WEB' },
  body: JSON.stringify({ loginId: 'user01', password: 'Econo1234!' })
});
const { accessExpiredTime } = await res.json();
// at, rt 쿠키는 브라우저가 자동 저장
```

### API 호출

```javascript
// 쿠키가 자동으로 전송됨 (별도 토큰 관리 불필요)
const res = await fetch('https://gateway.econovation.kr/api/programs', {
  credentials: 'include'  // ← 필수
});
```

### AT 만료 처리

```javascript
// 401 응답 시 재발급 시도
async function request(url, options = {}) {
  let res = await fetch(url, { credentials: 'include', ...options });

  if (res.status === 401) {
    // RT로 자동 갱신
    const reissue = await fetch('/api/v1/auth/reissue', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Client-Type': 'WEB' }
    });
    if (!reissue.ok) {
      window.location.href = '/login';  // RT도 만료 → 재로그인
      return;
    }
    // 원래 요청 재시도
    res = await fetch(url, { credentials: 'include', ...options });
  }
  return res;
}
```

### 다른 서비스로 이동 (SSO)

```javascript
// app-b.econovation.kr에서는 아무것도 할 필요 없음
// at 쿠키가 .econovation.kr이라 자동으로 전송됨
// 단, 앱 로드 시 쿠키 유효성 확인 권장:
async function checkAuth() {
  const res = await fetch('https://gateway.econovation.kr/api/health-check', {
    credentials: 'include'
  });
  if (res.status === 401) {
    // 재발급 시도 → 실패 시 로그인 페이지
  }
}
```

---

## 5. 알려진 한계

### ⚠️ AT 즉시 무효화 불가
JWT는 stateless라 만료 전 취소가 불가합니다.
로그아웃 시 쿠키는 삭제되지만, AT 값을 다른 곳에 저장한 경우 1시간 내 유효합니다.

**필요 시 해결책:** Redis 블랙리스트 구현 (ADR 작성 후 진행)

### ⚠️ 멀티 디바이스 로그인 제어 불가
현재 RT는 DB에 저장되지 않아 기기별 세션 관리가 불가합니다.
새 로그인 시 이전 기기의 RT는 여전히 유효합니다.

**필요 시 해결책:** RT를 DB에 저장하고 기기별 관리 구조 추가

### ⚠️ COOKIE_DOMAIN 미설정 시 SSO 미동작
`COOKIE_DOMAIN=.econovation.kr` 환경변수가 없으면 각 서버 도메인에만 쿠키가 귀속됩니다.

**운영 배포 필수 설정:**
```bash
COOKIE_DOMAIN=.econovation.kr
COOKIE_SECURE=true
```
