# Passport Claims Reference

Gateway를 통과하는 모든 요청에서 내부 서비스가 받는 회원 정보의 완전한 명세.

---

## 전체 흐름

```
클라이언트(WEB/APP)
  │  Authorization: Bearer <AT>
  │  또는 Cookie: at=<AT>
  ▼
api-gateway  (BearerToPassportFilter)
  │  RS256 JWT 검증 (JWKS → auth-api /oauth2/jwks)
  │  JWT 클레임 → Passport JSON → Base64 인코딩
  │  X-User-Passport: <Base64>
  ▼
내부 서비스  (econo-passport 라이브러리)
  │  PassportArgumentResolver
  │  Base64 디코딩 → JSON 파싱 → Passport 객체
  ▼
  @PassportAuth Passport passport → 컨트롤러 파라미터 주입
```

---

## 1. JWT Access Token 클레임 (auth-api 발급)

`LoginTokenService.encodeAt()`이 생성하는 RS256 JWT의 전체 클레임:

| 클레임 | 타입 | 예시 | 설명 |
|--------|------|------|------|
| `sub` | String | `"42"` | memberId (Long → String 변환) |
| `iss` | String | `"https://auth.econovation.kr"` | `AUTH_ISSUER_URI` 환경변수 |
| `iat` | NumericDate | `1780239351` | 발급 시각 (Unix epoch) |
| `exp` | NumericDate | `1780242951` | 만료 시각 (기본: 1시간 후) |
| `memberId` | Long | `42` | 회원 PK (DB ID) |
| `loginId` | String | `"honggildong"` | 로그인 아이디 |
| `name` | String | `"홍길동"` | 회원 이름 |
| `generation` | Integer | `30` | 기수 |
| `status` | String | `"AM"` | 활동 상태 (아래 참고) |
| `roles` | Array\<String\> | `["USER"]` | 권한 목록 |
| `token_type` | String | `"access"` | 항상 `"access"` (RT는 `"refresh"`) |

### status 값
| 값 | 설명 |
|----|------|
| `AM` | 활동 회원 (Active Member) |
| `RM` | 전역 회원 (Regular Member) |
| `CM` | 수료 회원 (Complete Member) |
| `OB` | OB 회원 |

### roles 값 (현재)
| 값 | 설명 |
|----|------|
| `USER` | 일반 회원 (현재 모든 회원) |
| `ADMIN` | 관리자 (수동 부여 예정) |
| `MANAGER` | 매니저 (수동 부여 예정) |

---

## 2. X-User-Passport 헤더

Gateway가 JWT 검증 후 `PassportBuilder.buildAndSerialize(jwt)`로 생성하여 내부 서비스에 전달하는 헤더.

```
X-User-Passport: <Base64(JSON)>
```

### Passport JSON 필드

| 필드 | 타입 | 출처 | 설명 |
|------|------|------|------|
| `memberId` | Long | JWT `sub` | 회원 PK |
| `loginId` | String | JWT `loginId` | 로그인 아이디 |
| `name` | String | JWT `name` | 회원 이름 |
| `generation` | Integer | JWT `generation` | 기수 |
| `status` | String | JWT `status` | 활동 상태 (AM/RM/CM/OB) |
| `roles` | Array\<String\> | JWT `roles` | 권한 목록 |
| `issuedAt` | LocalDateTime | JWT `iat` | 발급 시간 (ISO-8601) |
| `expiresAt` | LocalDateTime | JWT `exp` | 만료 시간 (ISO-8601) |

### Passport JSON 예시

```json
{
  "memberId": 42,
  "loginId": "honggildong",
  "name": "홍길동",
  "generation": 30,
  "status": "AM",
  "roles": ["USER"],
  "issuedAt": "2026-06-01T09:00:00",
  "expiresAt": "2026-06-01T10:00:00"
}
```

---

## 3. 내부 서비스에서 사용 (econo-passport)

### 기본 사용

```java
@GetMapping("/my-endpoint")
public ResponseEntity<?> getResource(
        @PassportAuth Passport passport) {
    Long memberId = passport.getMemberId();
    String name = passport.getName();
    // ...
}
```

### 권한 검증

```java
// USER 권한 필수
@PassportAuth(requiredRoles = "USER") Passport passport

// ADMIN 또는 MANAGER 중 하나 필수
@PassportAuth(requiredRoles = {"ADMIN", "MANAGER"}) Passport passport

// ADMIN 이상 (SUPER_ADMIN 포함) — 계층 지원
@PassportAuth(requiredRoles = "ADMIN", includeHigherRoles = true) Passport passport

// 선택적 인증 (비로그인 허용)
@PassportAuth(required = false) Passport passport  // null일 수 있음
```

### Passport 유틸리티 메서드

| 메서드 | 반환 | 설명 |
|--------|------|------|
| `isAdmin()` | boolean | `roles`에 `ADMIN` 포함 여부 |
| `isManager()` | boolean | `roles`에 `MANAGER` 포함 여부 |
| `hasRole(String)` | boolean | 특정 권한 보유 여부 |
| `hasAnyRole(String...)` | boolean | 하나 이상 권한 보유 여부 |
| `hasAllRoles(String...)` | boolean | 모든 권한 보유 여부 |
| `isExpired()` | boolean | 시간 기반 만료 여부 |
| `isValid()` | boolean | memberId 존재 여부 (구조 검증) |
| `isActive()` | boolean | 유효 + 미만료 종합 검증 |
| `isMember(Long)` | boolean | 특정 memberId 일치 여부 |
| `canAccessMember(Long)` | boolean | 자신이거나 ADMIN |

---

## 4. 권한 계층 (Roles)

```
SUPER_ADMIN
    └─ ADMIN
        └─ MANAGER
            └─ USER
```

`includeHigherRoles = true` 설정 시 ADMIN을 요구하면 SUPER_ADMIN도 통과.

---

## 5. Gateway 경로 보호 정책

| 경로 | 보호 여부 | 설명 |
|------|-----------|------|
| `/api/v1/auth/**` | 비보호 | 로그인/가입/재발급 |
| `/api/v1/auth/reissue` | 비보호 | RT로 재발급 (AT 없이 접근) |
| `/oauth2/jwks` | 비보호 | 공개키 조회 |
| `/.well-known/**` | 비보호 | OIDC Discovery |
| `/api/**` | **보호** | EEOS 등 내부 서비스 |

보호된 경로에 AT 없이 접근 시 Gateway가 `401 Unauthorized` 반환 (내부 서비스까지 요청 도달 안 함).

---

## 6. RT(Refresh Token) 클레임

RT는 최소 클레임만 포함 — X-User-Passport 생성에 사용되지 않음.

| 클레임 | 타입 | 설명 |
|--------|------|------|
| `sub` | String | memberId |
| `iss` | String | AUTH_ISSUER_URI |
| `iat` | NumericDate | 발급 시각 |
| `exp` | NumericDate | 만료 시각 (기본: 30일) |
| `token_type` | String | `"refresh"` |

재발급 시 `token_type == "refresh"` 검증으로 AT를 RT 자리에 사용하는 공격 차단.

---

## 관련 문서

- [ADR-0004: RS256 JWT with Passport Claims](./adr/0004-rs256-jwt-with-passport-claims.md)
- [ADR-0007: X-User-Passport Header Pattern](./adr/0007-x-user-passport-header-pattern.md)
- [use-passport 스킬](../.claude/skills/use-passport/SKILL.md)
- [register-service 스킬](../.claude/skills/register-service/SKILL.md)
